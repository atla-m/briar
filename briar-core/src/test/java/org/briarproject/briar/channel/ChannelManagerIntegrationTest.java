package org.briarproject.briar.channel;

import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.test.TestDatabaseConfigModule;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogSharingManager;
import org.briarproject.briar.api.blog.BlogPost;
import org.briarproject.briar.api.blog.BlogPostHeader;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.NoSuchChannelException;
import org.briarproject.briar.test.BriarIntegrationTest;
import org.briarproject.briar.test.BriarIntegrationTestComponent;
import org.briarproject.briar.test.DaggerBriarIntegrationTestComponent;
import org.junit.Before;
import org.junit.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.FETCHED;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.NO_MIRRORS;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.UNCHANGED;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.UNREACHABLE;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.NOT_SUPPORTED;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHAREABLE;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.briar.api.attachment.MediaConstants.FILE_CHUNK_PAYLOAD_LENGTH;
import static org.briarproject.bramble.util.StringUtils.getRandomString;
import static org.briarproject.briar.api.blog.MessageType.POST;
import static org.briarproject.briar.api.channel.ChannelConstants.STREAM_FORMAT_VERSION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelManagerIntegrationTest
		extends BriarIntegrationTest<BriarIntegrationTestComponent> {

	private ChannelManager channelManager0, channelManager1;
	private BlogManager blogManager0, blogManager1;
	private BlogSharingManager blogSharingManager0;

	@Before
	@Override
	public void setUp() throws Exception {
		super.setUp();
		channelManager0 = c0.getChannelManager();
		channelManager1 = c1.getChannelManager();
		blogManager0 = c0.getBlogManager();
		blogManager1 = c1.getBlogManager();
		blogSharingManager0 = c0.getBlogSharingManager();
	}

	@Test
	public void testChannelIsOwnedByItsOwnKeyPair() throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		assertEquals("Announcements", channel.getTitle());

		// The channel is published under its own key pair, not under the
		// identity that created it, so subscribers learn nothing about
		// who created it
		LocalAuthor creator = c0.getIdentityManager().getLocalAuthor();
		assertNotEquals(creator.getId(), channel.getLocalAuthor().getId());

		// It is a channel, and it isn't the creator's personal blog
		assertTrue(channel.getBlog().isChannel());
		assertNotEquals(blogManager0.getPersonalBlog(creator).getId(),
				channel.getBlogId());

		// We own it, and it is listed
		List<Channel> channels = channelManager0.getChannels();
		assertEquals(1, channels.size());
		assertEquals(channel.getBlogId(), channels.get(0).getBlogId());
		Channel found = channelManager0.getChannel(channel.getBlogId());
		assertNotEquals(null, found);
	}

	@Test
	public void testSubscriberReceivesChannelPosts() throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();

		// The subscriber derives the same channel from its key pair and
		// title alone, which is what a channel link will carry
		Blog subscribed = blogFactory
				.createChannelBlog(channel.getLocalAuthor());
		assertEquals(g, subscribed.getId());
		db1.transaction(false, txn -> blogManager1.addBlog(txn, subscribed));
		shareBothWays(g);

		// The owner posts
		String text = getRandomString(42);
		channelManager0.post(g, text);
		Collection<BlogPostHeader> headers0 = blogManager0.getPostHeaders(g);
		assertEquals(1, headers0.size());

		sync0To1(1, true);

		// The subscriber has the post, authored by the channel
		Collection<BlogPostHeader> headers1 = blogManager1.getPostHeaders(g);
		assertEquals(1, headers1.size());
		BlogPostHeader h = headers1.iterator().next();
		assertEquals(channel.getLocalAuthor().getId(), h.getAuthor().getId());
		assertEquals(text, blogManager1.getPostText(h.getId()));

		// The subscriber doesn't own the channel, so can't post to it
		assertNull(channelManager1.getChannel(g));
		assertTrue(channelManager1.getChannels().isEmpty());
		try {
			channelManager1.post(g, "not from the owner");
			fail();
		} catch (NoSuchChannelException expected) {
			// Expected
		}
	}

	@Test
	public void testSubscriberCannotForgeAChannelPost() throws Exception {
		// The point of a channel: only the holder of its key pair can
		// post. A subscriber who writes a post into the channel's group,
		// signed with their own identity, must be rejected.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		Blog subscribed = blogFactory
				.createChannelBlog(channel.getLocalAuthor());
		db1.transaction(false, txn -> blogManager1.addBlog(txn, subscribed));
		shareBothWays(g);

		BlogPost forged = blogPostFactory.createBlogPost(g,
				c1.getClock().currentTimeMillis(), null, author1,
				"I am not the owner");
		blogManager1.addLocalPost(forged);

		// The owner's device rejects it as invalid
		sync1To0(1, false);
		assertTrue(blogManager0.getPostHeaders(g).isEmpty());
	}

	@Test
	public void testDeletingAChannelRemovesItsKeyPair() throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));

		channelManager0.deleteChannel(g);
		assertTrue(channelManager0.getChannels().isEmpty());
		assertNull(channelManager0.getChannel(g));
		try {
			channelManager0.post(g, "no key any more");
			fail();
		} catch (NoSuchChannelException expected) {
			// Expected
		}
	}

	@Test
	public void testChannelTitleIsTruncatedNotRejected() throws Exception {
		Channel channel = channelManager0.createChannel(getRandomString(200));
		assertTrue(channel.getTitle().length() <= 50);
		assertFalse(channel.getTitle().isEmpty());
	}

	@Test
	public void testChannelIsNotOfferedToOlderClients() throws Exception {
		// Sharing is gated on the major version, which channels don't
		// change, so the blog sharing client's minor version decides
		// whether a contact is offered a channel
		Channel channel = channelManager0.createChannel("Announcements");
		Contact contact = contactManager0.getContact(contactId1From0);

		// Both devices run this build, so the channel can be shared
		assertEquals(SHAREABLE, db0.transactionWithResult(true, txn ->
				blogSharingManager0.getSharingStatus(txn,
						channel.getBlogId(), contact)));

		// A personal blog is shareable either way
		Blog personal = blogManager0.getPersonalBlog(author1);
		assertNotEquals(NOT_SUPPORTED, db0.transactionWithResult(true, txn ->
				blogSharingManager0.getSharingStatus(txn, personal.getId(),
						contact)));
	}

	@Test
	public void testImportingAStreamDeliversPostsWithoutSyncing()
			throws Exception {
		// The point of the stream: a device that has no sync relationship
		// for this channel still gets its posts, by fetching a file the
		// owner published
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		String text = getRandomString(42);
		channelManager0.post(g, text);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, out);

		// The subscriber has only the title and public key, which is what
		// a channel link carries
		Blog subscribed = channelManager1.subscribe("Announcements",
				channel.getLocalAuthor().getPublicKey());
		assertEquals(g, subscribed.getId());
		assertTrue(blogManager1.getPostHeaders(g).isEmpty());

		assertEquals(1, channelManager1.importChannel(
				new ByteArrayInputStream(out.toByteArray())));
		awaitPendingMessageDelivery(1);

		Collection<BlogPostHeader> headers = blogManager1.getPostHeaders(g);
		assertEquals(1, headers.size());
		BlogPostHeader h = headers.iterator().next();
		assertEquals(channel.getLocalAuthor().getId(), h.getAuthor().getId());
		assertEquals(text, blogManager1.getPostText(h.getId()));
	}

	@Test
	public void testRejectsAForgedPostInAStream() throws Exception {
		// Whoever serves the stream is not trusted: a post that isn't
		// signed by the channel must be rejected, not stored
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		Blog subscribed = channelManager1.subscribe("Announcements",
				channel.getLocalAuthor().getPublicKey());
		assertEquals(g, subscribed.getId());

		// A post into the channel's group, signed by someone else
		BlogPost forged = blogPostFactory.createBlogPost(g,
				c1.getClock().currentTimeMillis(), null, author1,
				"I am not the owner");

		assertEquals(1, channelManager1.importChannel(
				new ByteArrayInputStream(buildStream(subscribed,
						forged.getMessage().getTimestamp(),
						forged.getMessage().getBody()))));
		awaitPendingMessageValidation(1);

		// It was read from the stream but not accepted
		assertTrue(blogManager1.getPostHeaders(g).isEmpty());
	}

	@Test(expected = NoSuchChannelException.class)
	public void testRejectsAStreamForAChannelWeDidNotSubscribeTo()
			throws Exception {
		// A file can't add channels we never asked for
		Channel channel = channelManager0.createChannel("Announcements");
		channelManager0.post(channel.getBlogId(), getRandomString(42));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(channel.getBlogId(), out);

		channelManager1.importChannel(
				new ByteArrayInputStream(out.toByteArray()));
	}

	@Test
	public void testSubscribingFromALinkGivesTheSameChannel()
			throws Exception {
		// A link carries a channel's title and public key, which is all
		// anyone needs to derive the same channel and start accepting its
		// posts. It says nothing about who created the channel.
		Channel channel = channelManager0.createChannel("Announcements");
		String link = channelManager0.getChannelLink(channel.getBlogId());
		assertTrue(link.startsWith("briar-channel://"));

		Blog subscribed = channelManager1.subscribeFromLink(link);
		assertEquals(channel.getBlogId(), subscribed.getId());
		assertTrue(subscribed.isChannel());
		assertEquals("Announcements", subscribed.getName());

		// Subscribing doesn't make us the owner
		assertNull(channelManager1.getChannel(subscribed.getId()));
	}

	@Test
	public void testSubscribingFromALinkIsEnoughToImportAStream()
			throws Exception {
		// The whole loop with no contact relationship: a link to subscribe
		// and a published file to read
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		String text = getRandomString(42);
		channelManager0.post(g, text);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, out);

		channelManager1.subscribeFromLink(
				channelManager0.getChannelLink(g));
		assertEquals(1, channelManager1.importChannel(
				new ByteArrayInputStream(out.toByteArray())));
		awaitPendingMessageDelivery(1);

		Collection<BlogPostHeader> headers = blogManager1.getPostHeaders(g);
		assertEquals(1, headers.size());
		assertEquals(text,
				blogManager1.getPostText(headers.iterator().next().getId()));
	}

	@Test
	public void testFetchesAChannelFromAMirror() throws Exception {
		// The mirror serves a file it cannot alter; the subscriber checks
		// every post against the channel's key
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		String text = getRandomString(42);
		channelManager0.post(g, text);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(
				new Buffer().write(exportChannel(g))));
		server.start();
		try {
			String url = server.url("/announcements.briar").toString();
			channelManager0.setMirrors(g, singletonList(url));
			// The link now carries the mirror, so subscribing is enough
			channelManager1.subscribeFromLink(
					channelManager0.getChannelLink(g));
			assertEquals(singletonList(url), channelManager1.getMirrors(g));

			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);

			Collection<BlogPostHeader> headers =
					blogManager1.getPostHeaders(g);
			assertEquals(1, headers.size());
			assertEquals(text, blogManager1
					.getPostText(headers.iterator().next().getId()));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testDoesNotRefetchAnUnchangedChannel() throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse()
				.setHeader("ETag", "\"v1\"")
				.setBody(new Buffer().write(exportChannel(g))));
		server.enqueue(new MockResponse().setResponseCode(304));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);

			// The second fetch sends the tag the mirror gave us, and the
			// mirror says nothing has changed, so nothing is downloaded
			assertEquals(UNCHANGED,
					channelManager1.fetchChannel(g).getOutcome());
			server.takeRequest();
			RecordedRequest second = server.takeRequest();
			assertEquals("\"v1\"", second.getHeader("If-None-Match"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testTriesTheNextMirrorWhenOneFails() throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));

		MockWebServer broken = new MockWebServer();
		broken.enqueue(new MockResponse().setResponseCode(503));
		broken.start();
		MockWebServer working = new MockWebServer();
		working.enqueue(new MockResponse().setBody(
				new Buffer().write(exportChannel(g))));
		working.start();
		try {
			channelManager0.setMirrors(g, asList(
					broken.url("/c.briar").toString(),
					working.url("/c.briar").toString()));
			channelManager1.subscribeFromLink(
					channelManager0.getChannelLink(g));
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			assertEquals(1, blogManager1.getPostHeaders(g).size());
		} finally {
			broken.shutdown();
			working.shutdown();
		}
	}

	@Test
	public void testRejectsAForgedPostServedByAMirror() throws Exception {
		// A mirror that tries to put words in the channel's mouth
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		Blog subscribed = channelManager1.subscribe("Announcements",
				channel.getLocalAuthor().getPublicKey());
		BlogPost forged = blogPostFactory.createBlogPost(g,
				c1.getClock().currentTimeMillis(), null, author1,
				"I am not the owner");

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(
				buildStream(subscribed, forged.getMessage().getTimestamp(),
						forged.getMessage().getBody()))));
		server.start();
		try {
			channelManager1.setMirrors(g,
					singletonList(server.url("/c.briar").toString()));
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageValidation(1);
			assertTrue(blogManager1.getPostHeaders(g).isEmpty());
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testUnreachableMirrorIsNotReportedAsUpToDate()
			throws Exception {
		// A reader whose mirrors are blocked must not be told the channel
		// is up to date: they may be missing everything since last time
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		MockWebServer server = new MockWebServer();
		server.start();
		String url = server.url("/c.briar").toString();
		server.shutdown();

		channelManager0.setMirrors(g, singletonList(url));
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		assertEquals(UNREACHABLE,
				channelManager1.fetchChannel(g).getOutcome());
	}

	@Test
	public void testChannelWithNoMirrorsSaysSo() throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		assertEquals(NO_MIRRORS, channelManager0
				.fetchChannel(channel.getBlogId()).getOutcome());
	}

	@Test
	public void testSubscriptionsAreListedSeparatelyFromOurOwnChannels()
			throws Exception {
		// A reader has to be able to find a channel they subscribe to, to
		// fetch it or see where it is published
		Channel ours = channelManager0.createChannel("Ours");
		Channel theirs = channelManager1.createChannel("Theirs");
		channelManager0.subscribeFromLink(
				channelManager1.getChannelLink(theirs.getBlogId()));

		// We own one and subscribe to the other
		List<Channel> owned = channelManager0.getChannels();
		assertEquals(1, owned.size());
		assertEquals(ours.getBlogId(), owned.get(0).getBlogId());

		List<Blog> subscriptions = channelManager0.getSubscriptions();
		assertEquals(1, subscriptions.size());
		assertEquals(theirs.getBlogId(), subscriptions.get(0).getId());

		// Our own channel is not listed as a subscription, and a personal
		// blog is not listed as a channel at all
		for (Blog b : subscriptions) {
			assertNotEquals(ours.getBlogId(), b.getId());
			assertTrue(b.isChannel());
		}
	}

	@Test
	public void testUnsubscribingLeavesOurOwnChannelsAlone()
			throws Exception {
		Channel ours = channelManager0.createChannel("Ours");
		Channel theirs = channelManager1.createChannel("Theirs");
		channelManager0.subscribeFromLink(
				channelManager1.getChannelLink(theirs.getBlogId()));

		channelManager0.unsubscribe(theirs.getBlogId());
		assertTrue(channelManager0.getSubscriptions().isEmpty());
		assertEquals(1, channelManager0.getChannels().size());

		// A channel we created is deleted, not unsubscribed from, so its
		// key pair goes with it
		try {
			channelManager0.unsubscribe(ours.getBlogId());
			fail();
		} catch (IllegalArgumentException expected) {
			// Expected
		}
		assertEquals(1, channelManager0.getChannels().size());
	}

	@Test
	public void testChannelPostCanCarryAnImage() throws Exception {
		// A post's attachment headers are covered by its signature, so a
		// mirror or a member can't swap or remove what it carries
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		AttachmentHeader image = new AttachmentHeader(g,
				new MessageId(getRandomId()), "image/jpeg");
		BlogPost post = blogPostFactory.createBlogPost(g,
				c0.getClock().currentTimeMillis(), null,
				channel.getLocalAuthor(), "Look at this",
				singletonList(image), emptyList());

		// It parses back as a valid post of this channel
		BdfList body = c0.getClientHelper()
				.toList(post.getMessage().getBody());
		assertEquals(4, body.size());
		assertEquals(POST.getInt(), body.getInt(0).intValue());
		assertEquals("Look at this", body.getString(1));
		assertEquals(1, body.getList(2).size());
	}

	@Test
	public void testChannelPostWithAnImageIsAccepted() throws Exception {
		// The positive half of the pair below: a properly signed post
		// carrying an image must be delivered, with its header intact
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		AttachmentHeader image = new AttachmentHeader(g,
				new MessageId(getRandomId()), "image/jpeg");
		BlogPost post = blogPostFactory.createBlogPost(g,
				c0.getClock().currentTimeMillis(), null,
				channel.getLocalAuthor(), "Look at this",
				singletonList(image), emptyList());

		Blog subscribed = channelManager1.subscribe("Announcements",
				channel.getLocalAuthor().getPublicKey());
		assertEquals(1, channelManager1.importChannel(
				new ByteArrayInputStream(buildStream(subscribed,
						post.getMessage().getTimestamp(),
						post.getMessage().getBody()))));
		awaitPendingMessageDelivery(1);

		Collection<BlogPostHeader> headers = blogManager1.getPostHeaders(g);
		assertEquals(1, headers.size());
		assertEquals("Look at this", blogManager1
				.getPostText(headers.iterator().next().getId()));
	}

	@Test
	public void testChannelPostSignatureCoversItsAttachments()
			throws Exception {
		// Changing a header after signing must invalidate the post
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		AttachmentHeader image = new AttachmentHeader(g,
				new MessageId(getRandomId()), "image/jpeg");
		BlogPost post = blogPostFactory.createBlogPost(g,
				c0.getClock().currentTimeMillis(), null,
				channel.getLocalAuthor(), "Look at this",
				singletonList(image), emptyList());

		// Swap the attachment for a different one, keeping the signature
		BdfList body = c0.getClientHelper()
				.toList(post.getMessage().getBody());
		BdfList swapped = BdfList.of(body.get(0), body.get(1),
				BdfList.of(BdfList.of(new MessageId(getRandomId()),
						"image/jpeg")), body.get(3));
		Message tampered = c0.getClientHelper().createMessage(g,
				post.getMessage().getTimestamp(), swapped);

		Blog subscribed = channelManager1.subscribe("Announcements",
				channel.getLocalAuthor().getPublicKey());
		assertEquals(g, subscribed.getId());
		assertEquals(1, channelManager1.importChannel(
				new ByteArrayInputStream(buildStream(subscribed,
						tampered.getTimestamp(), tampered.getBody()))));
		awaitPendingMessageValidation(1);
		assertTrue(blogManager1.getPostHeaders(g).isEmpty());
	}

	@Test
	public void testChannelCarriesAFileToASubscriber() throws Exception {
		// The payoff of building chunking as a shared store: a channel
		// gets files with the manifest-bound chunks already in place
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		byte[] fileBytes = getRandomBytes(FILE_CHUNK_PAYLOAD_LENGTH * 2);
		FileHeader file = blogManager0.addLocalFile(g,
				c0.getClock().currentTimeMillis(), "notice.pdf",
				"application/pdf",
				() -> new ByteArrayInputStream(fileBytes));
		assertEquals(2, file.getChunkCount());

		BlogPost post = blogPostFactory.createBlogPost(g,
				c0.getClock().currentTimeMillis() + 1, null,
				channel.getLocalAuthor(), "Here is the notice", emptyList(),
				singletonList(file));
		blogManager0.addLocalPost(post);
		assertTrue(blogManager0.getFileStatus(file).isComplete());

		// The subscriber has only the link, and gets the post, the
		// manifest and both chunks from the published file
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, out);
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		assertEquals(4, channelManager1.importChannel(
				new ByteArrayInputStream(out.toByteArray())));
		awaitPendingMessageDelivery(4);

		Collection<BlogPostHeader> headers = blogManager1.getPostHeaders(g);
		assertEquals(1, headers.size());
		FileHeader received = blogManager1.getFileHeader(g,
				file.getManifestId());
		assertEquals("notice.pdf", received.getName());
		assertTrue(blogManager1.getFileStatus(received).isComplete());
		ByteArrayOutputStream read = new ByteArrayOutputStream();
		copyAndClose(blogManager1.getFile(received), read);
		assertArrayEquals(fileBytes, read.toByteArray());
	}

	private byte[] exportChannel(GroupId g) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, out);
		return out.toByteArray();
	}

	private void subscribeWithMirror(GroupId g, String url) throws Exception {
		channelManager0.setMirrors(g, singletonList(url));
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
	}

	@Test
	public void testRejectsMalformedLinks() throws Exception {
		expectBadLink("");
		expectBadLink("briar-channel://");
		expectBadLink("briar-channel://not!base32");
		// A well-formed handshake link is not a channel link
		expectBadLink("briar://" + getRandomString(53).toLowerCase());
	}

	private void expectBadLink(String link) throws Exception {
		try {
			channelManager1.subscribeFromLink(link);
			fail("Accepted " + link);
		} catch (FormatException | IllegalArgumentException expected) {
			// Expected
		}
	}

	/**
	 * Builds a channel stream by hand, so a test can put something in it
	 * that the owner never signed.
	 */
	private byte[] buildStream(Blog blog, long timestamp, byte[] body)
			throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(c1.getClientHelper().toByteArray(BdfList.of(
				STREAM_FORMAT_VERSION, blog.getGroup().getDescriptor())));
		out.write(c1.getClientHelper()
				.toByteArray(BdfList.of(timestamp, body)));
		return out.toByteArray();
	}

	private void shareBothWays(GroupId g) throws Exception {
		db0.transaction(false, txn ->
				db0.setGroupVisibility(txn, contactId1From0, g, SHARED));
		db1.transaction(false, txn ->
				db1.setGroupVisibility(txn, contactId0From1, g, SHARED));
	}

	@Override
	protected void createComponents() {
		BriarIntegrationTestComponent component =
				DaggerBriarIntegrationTestComponent.builder().build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(component);
		component.inject(this);

		c0 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t0Dir))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c0);

		c1 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t1Dir))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c1);

		c2 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t2Dir))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c2);
	}
}
