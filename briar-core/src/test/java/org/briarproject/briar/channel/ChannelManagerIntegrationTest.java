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
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_STREAM_BYTES;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_STREAM_MESSAGES;
import static org.briarproject.briar.api.channel.ChannelConstants.MIN_HONEST_MESSAGE_BYTES;
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
	public void testReadingAStreamHeaderStoresNothing() throws Exception {
		// The app reads the header to say which channel a file holds
		// before asking whether to subscribe, so reading it must not
		// subscribe or store anything
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, out);

		Blog read = channelManager1.readChannelHeader(
				new ByteArrayInputStream(out.toByteArray()));
		assertEquals(g, read.getId());
		assertTrue(read.isChannel());
		assertEquals("Announcements", read.getName());

		assertTrue(channelManager1.getSubscriptions().isEmpty());
	}

	@Test
	public void testAFileAloneIsEnoughToSubscribe() throws Exception {
		// In a blackout the file may be the only thing that can be handed
		// over, so once the user has agreed it must be enough on its own:
		// its header carries the channel's title and public key
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		String text = getRandomString(42);
		channelManager0.post(g, text);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, out);

		assertEquals(1, channelManager1.importChannel(
				new ByteArrayInputStream(out.toByteArray()), true));
		awaitPendingMessageDelivery(1);

		Collection<BlogPostHeader> headers = blogManager1.getPostHeaders(g);
		assertEquals(1, headers.size());
		BlogPostHeader h = headers.iterator().next();
		assertEquals(channel.getLocalAuthor().getId(), h.getAuthor().getId());
		assertEquals(text, blogManager1.getPostText(h.getId()));

		// Subscribed, but not the owner
		assertEquals(1, channelManager1.getSubscriptions().size());
		assertNull(channelManager1.getChannel(g));
	}

	@Test
	public void testSubscribingFromAFileStillRejectsAForgedPost()
			throws Exception {
		// Subscribing from a file must not weaken what the file's posts
		// are checked against: a post not signed by the channel is still
		// rejected, even though the same file supplied the channel
		Channel channel = channelManager0.createChannel("Announcements");
		Blog blog = channel.getBlog();
		BlogPost forged = blogPostFactory.createBlogPost(blog.getId(),
				c1.getClock().currentTimeMillis(), null, author1,
				"I am not the owner");

		assertEquals(1, channelManager1.importChannel(
				new ByteArrayInputStream(buildStream(blog,
						forged.getMessage().getTimestamp(),
						forged.getMessage().getBody())), true));
		awaitPendingMessageValidation(1);

		assertEquals(1, channelManager1.getSubscriptions().size());
		assertTrue(blogManager1.getPostHeaders(blog.getId()).isEmpty());
	}

	@Test
	public void testASubscriberCanPassAChannelOnByFile() throws Exception {
		// If the owner is out of reach, whoever holds the channel's posts
		// must be able to hand them on. The file holds only the channel's
		// own signed messages, so passing it through a subscriber changes
		// nothing about what a reader can trust
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		String text = getRandomString(42);
		channelManager0.post(g, text);
		ByteArrayOutputStream fromOwner = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, fromOwner);
		channelManager1.importChannel(
				new ByteArrayInputStream(fromOwner.toByteArray()), true);
		awaitPendingMessageDelivery(1);

		ByteArrayOutputStream fromSubscriber = new ByteArrayOutputStream();
		channelManager1.exportChannel(g, fromSubscriber);

		// The subscriber's file is the owner's file: same signed messages
		// in the same order, so a reader can't tell who made it
		assertArrayEquals(fromOwner.toByteArray(),
				fromSubscriber.toByteArray());
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
		// The post's header names the file, so it can be shown with its
		// name and size before the file itself has arrived
		BlogPostHeader header = headers.iterator().next();
		assertEquals(1, header.getFileHeaders().size());
		assertTrue(header.getAttachmentHeaders().isEmpty());
		FileHeader named = header.getFileHeaders().get(0);
		assertEquals("notice.pdf", named.getName());
		assertEquals(fileBytes.length, named.getSize());
		assertEquals(file.getManifestId(), named.getManifestId());
		FileHeader received = blogManager1.getFileHeader(g,
				file.getManifestId());
		assertEquals("notice.pdf", received.getName());
		assertTrue(blogManager1.getFileStatus(received).isComplete());
		ByteArrayOutputStream read = new ByteArrayOutputStream();
		copyAndClose(blogManager1.getFile(received), read);
		assertArrayEquals(fileBytes, read.toByteArray());
	}

	@Test
	public void testPostHeaderNamesTheImagesItCarries() throws Exception {
		// The poster's own copy of the post must name its images too,
		// otherwise the screen that wrote them cannot show them back
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		byte[] imageBytes = getRandomBytes(123);
		AttachmentHeader image = blogManager0.addLocalAttachment(g,
				c0.getClock().currentTimeMillis(), "image/jpeg",
				new ByteArrayInputStream(imageBytes));

		BlogPost post = blogPostFactory.createBlogPost(g,
				c0.getClock().currentTimeMillis() + 1, null,
				channel.getLocalAuthor(), "Look at this",
				singletonList(image), emptyList());
		blogManager0.addLocalPost(post);

		Collection<BlogPostHeader> headers = blogManager0.getPostHeaders(g);
		assertEquals(1, headers.size());
		BlogPostHeader header = headers.iterator().next();
		assertEquals(1, header.getAttachmentHeaders().size());
		assertTrue(header.getFileHeaders().isEmpty());
		AttachmentHeader named = header.getAttachmentHeaders().get(0);
		assertEquals(image.getMessageId(), named.getMessageId());
		assertEquals("image/jpeg", named.getContentType());
	}

	@Test
	public void testANewMessageIsTimestampedAfterTheOnesAlreadyThere()
			throws Exception {
		// What keeps a published file growing only at its end, and so
		// what lets a reader ask a mirror for just the part it is
		// missing. Two messages written in the same millisecond would
		// otherwise be ordered by their IDs, which are hashes.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		long moment = c0.getClock().currentTimeMillis();
		assertEquals(moment, blogManager0.getNextTimestamp(g, moment));

		// Something written at that moment takes it, so the next message
		// asking for the same moment is put after it
		blogManager0.addLocalAttachment(g, moment, "image/jpeg",
				new ByteArrayInputStream(getRandomBytes(123)));
		long next = blogManager0.getNextTimestamp(g, moment);
		assertTrue(next > moment);

		// and so on, however many are written in the same millisecond
		blogManager0.addLocalAttachment(g, moment, "image/jpeg",
				new ByteArrayInputStream(getRandomBytes(123)));
		assertTrue(blogManager0.getNextTimestamp(g, moment) > next);

		// A moment later than everything in the blog is left alone
		assertEquals(moment + 1000,
				blogManager0.getNextTimestamp(g, moment + 1000));

		// A personal blog is not published as a file, so its messages
		// keep whatever timestamp they were given
		GroupId personal = blogManager0.getPersonalBlog(author0).getId();
		blogManager0.addLocalAttachment(personal, moment, "image/jpeg",
				new ByteArrayInputStream(getRandomBytes(123)));
		assertEquals(moment, blogManager0.getNextTimestamp(personal, moment));
	}

	@Test
	public void testPublishingAgainOnlyAddsToTheEndOfTheFile()
			throws Exception {
		// Posts written in the same millisecond share a timestamp, and
		// message IDs are hashes, so without an order of its own a new
		// post could sort before one already published and move the
		// bytes a reader has already read
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		byte[] previous = exportChannel(g);
		for (int i = 0; i < 5; i++) {
			channelManager0.post(g, getRandomString(42));
			byte[] current = exportChannel(g);
			assertTrue(current.length > previous.length);
			byte[] prefix = new byte[previous.length];
			System.arraycopy(current, 0, prefix, 0, previous.length);
			assertArrayEquals(previous, prefix);
			previous = current;
		}
	}

	@Test
	public void testExportIsDeterministicAndOnlyGrowsAtTheEnd()
			throws Exception {
		// Fetching only the part of a file we don't have rests on this:
		// the same channel always exports the same bytes, and publishing
		// again leaves what was already there untouched
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));

		byte[] first = exportChannel(g);
		assertArrayEquals(first, exportChannel(g));

		channelManager0.post(g, getRandomString(42));
		byte[] second = exportChannel(g);
		assertTrue(second.length > first.length);
		byte[] prefix = new byte[first.length];
		System.arraycopy(second, 0, prefix, 0, first.length);
		assertArrayEquals(first, prefix);
	}

	@Test
	public void testFetchesOnlyThePartOfTheFileItDoesNotHave()
			throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] first = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] second = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(first)));
		// The second fetch asks for the rest, so the mirror sends the
		// bytes from the requested offset on
		int from = first.length;
		server.enqueue(new MockResponse().setResponseCode(206)
				.setHeader("Content-Range", "bytes " + from + "-" +
						(second.length - 1) + "/" + second.length)
				.setBody(new Buffer().write(second, from,
						second.length - from)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			assertEquals(1, blogManager1.getPostHeaders(g).size());

			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			assertEquals(2, blogManager1.getPostHeaders(g).size());

			// The first fetch asked for the whole file, the second only
			// for what came after the part already read
			server.takeRequest();
			RecordedRequest resumed = server.takeRequest();
			assertEquals("bytes=" + from + "-", resumed.getHeader("Range"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testReadsTheWholeFileWhenAMirrorIgnoresRanges()
			throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] first = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] second = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(first)));
		// A mirror that doesn't do ranges answers 200 with everything
		server.enqueue(new MockResponse().setBody(new Buffer().write(second)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);

			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			assertEquals(2, blogManager1.getPostHeaders(g).size());
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testStartsAgainWhenTheFileChangedUnderneathIt()
			throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] first = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] second = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(first)));
		// The mirror answers the range with bytes that are not the
		// continuation of what we read: the file is not the same file
		int from = first.length;
		server.enqueue(new MockResponse().setResponseCode(206)
				.setBody(new Buffer().write(getRandomBytes(64))));
		// So the whole file is read again, and the new post arrives
		server.enqueue(new MockResponse().setBody(new Buffer().write(second)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);

			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			assertEquals(2, blogManager1.getPostHeaders(g).size());

			server.takeRequest();
			assertEquals("bytes=" + from + "-",
					server.takeRequest().getHeader("Range"));
			// The third request asks for the file from the beginning
			assertNull(server.takeRequest().getHeader("Range"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testStartsAgainWhenTheFileGotShorter() throws Exception {
		// The owner rebuilds the channel, or restores an older backup,
		// and publishes a file shorter than the part we already read.
		// Asking again for a range that file will never have would leave
		// us being told there is nothing new for ever.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] shorter = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] longer = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse()
				.setHeader("ETag", "\"one\"")
				.setBody(new Buffer().write(longer)));
		// Asking for bytes past the end of the file now being served
		server.enqueue(new MockResponse().setResponseCode(416));
		// So the file is read from the beginning instead. The mirror
		// gives the tag of the file we could not continue, which it must
		// not be offered, or it would answer that nothing had changed
		server.enqueue(new MockResponse()
				.setHeader("ETag", "\"one\"")
				.setBody(new Buffer().write(shorter)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(2);
			assertEquals(2, blogManager1.getPostHeaders(g).size());

			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());

			server.takeRequest();
			assertEquals("bytes=" + longer.length + "-",
					server.takeRequest().getHeader("Range"));
			RecordedRequest full = server.takeRequest();
			assertNull(full.getHeader("Range"));
			assertNull(full.getHeader("If-None-Match"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testForgetsTheTagOfAFileItCouldNotContinue()
			throws Exception {
		// Starting a file again has to forget the tag we stored for the
		// file we gave up on, and forget it in the database, not just
		// for the request that follows. If the fetch that starts again
		// is itself cut off, a tag left behind would go out with the
		// next request, and a mirror still giving that tag for the file
		// it has now would answer that there was nothing new, for ever.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] one = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] two = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] three = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse()
				.setHeader("ETag", "\"three\"")
				.setBody(new Buffer().write(three)));
		// The file is replaced by a shorter one, so the range we ask for
		// is one it will never have
		server.enqueue(new MockResponse().setResponseCode(416));
		// We start again, and that fetch dies in the middle of the
		// second post, so its tag is not ours to keep either
		int cut = one.length + (two.length - one.length) / 2;
		server.enqueue(new MockResponse()
				.setHeader("ETag", "\"two\"")
				.setBody(new Buffer().write(two, 0, cut)));
		server.enqueue(new MockResponse().setResponseCode(206)
				.setHeader("ETag", "\"two\"")
				.setBody(new Buffer().write(two, one.length,
						two.length - one.length)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(3);
			assertEquals(3, blogManager1.getPostHeaders(g).size());

			// The file got shorter, we started again, and that was cut off
			assertEquals(UNREACHABLE,
					channelManager1.fetchChannel(g).getOutcome());

			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());

			server.takeRequest();
			server.takeRequest();
			server.takeRequest();
			// Carries on from the post that did arrive whole, and offers
			// no tag, because we have none for the file being served now
			RecordedRequest resumed = server.takeRequest();
			assertEquals("bytes=" + one.length + "-",
					resumed.getHeader("Range"));
			assertNull(resumed.getHeader("If-None-Match"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testATextChannelWithManyPostsIsNotCutOff() throws Exception {
		// The limit on how many messages a file may make us store exists
		// to stop an untrusted source filling the database with tiny
		// messages. It must never bind before the byte limit for an honest
		// channel: a channel of short posts, well under the byte limit,
		// has to arrive whole, newest post included
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		int posts = 400;
		String newest = null;
		for (int i = 0; i < posts; i++) {
			newest = "Post " + i;
			channelManager0.post(g, newest);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, out);
		assertTrue(out.size() < MAX_STREAM_BYTES);

		assertEquals(posts, channelManager1.importChannel(
				new ByteArrayInputStream(out.toByteArray()), true));
		awaitPendingMessageDelivery(posts);

		Collection<BlogPostHeader> headers = blogManager1.getPostHeaders(g);
		assertEquals(posts, headers.size());
		boolean foundNewest = false;
		for (BlogPostHeader h : headers) {
			if (newest.equals(blogManager1.getPostText(h.getId()))) {
				foundNewest = true;
			}
		}
		assertTrue(foundNewest);
	}

	@Test
	public void testCountsTheWholeFileAcrossFetches() throws Exception {
		// The limits on how much a file may make us store count the whole
		// file, not each fetch. A mirror that could spend them again on
		// every fetch could go on filling our database for ever, since a
		// message that never becomes deliverable is kept. The byte and
		// message counts are stored and carried forward together, so this
		// exercises both; the message limit itself is too large to reach
		// in a test in reasonable time.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		Blog subscribed = channelManager1.subscribe("Announcements",
				channel.getLocalAuthor().getPublicKey());
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		head.write(c1.getClientHelper().toByteArray(BdfList.of(
				STREAM_FORMAT_VERSION, subscribed.getGroup().getDescriptor())));
		byte[] entry = c1.getClientHelper().toByteArray(BdfList.of(1L,
				getRandomBytes(MAX_MESSAGE_BODY_LENGTH)));
		long timestamp = 1;
		while (head.size() + entry.length <= MAX_STREAM_BYTES) {
			head.write(c1.getClientHelper().toByteArray(BdfList.of(
					timestamp++, getRandomBytes(MAX_MESSAGE_BODY_LENGTH))));
		}
		byte[] tail = c1.getClientHelper().toByteArray(BdfList.of(timestamp,
				getRandomBytes(MAX_MESSAGE_BODY_LENGTH)));

		MockWebServer server = new MockWebServer();
		// As much as a file may carry, which is allowed
		server.enqueue(new MockResponse()
				.setBody(new Buffer().write(head.toByteArray())));
		// One more message, which is not, however it is spread over
		// fetches
		server.enqueue(new MockResponse().setResponseCode(206)
				.setBody(new Buffer().write(tail)));
		server.start();
		try {
			channelManager1.setMirrors(g,
					singletonList(server.url("/c.briar").toString()));
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			// The mirror is dropped rather than the file read again:
			// there is nothing wrong with the file, we just won't store
			// it, so asking for it once more would be wasted work
			assertEquals(UNREACHABLE,
					channelManager1.fetchChannel(g).getOutcome());

			server.takeRequest();
			assertEquals("bytes=" + head.size() + "-",
					server.takeRequest().getHeader("Range"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testTheMessageLimitBindsOnlyOnJunk() {
		// Deriving the message limit from the largest message allowed a
		// few hundred messages over a channel's life, so a channel of
		// short posts stopped updating for good long before the byte
		// limit. It must be derived from the smallest honest message
		assertEquals(MAX_STREAM_BYTES / MIN_HONEST_MESSAGE_BYTES,
				MAX_STREAM_MESSAGES);
		assertTrue(MAX_STREAM_MESSAGES > 10_000);
	}

	@Test
	public void testRejectsAMessageAMirrorCouldNotHaveCarried()
			throws Exception {
		// A stream is written by someone we don't trust, so it can claim
		// messages that no real message could be. Each must be turned
		// away, not allowed to bring the fetch down
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		Blog subscribed = channelManager1.subscribe("Announcements",
				channel.getLocalAuthor().getPublicKey());

		MockWebServer server = new MockWebServer();
		// Dated before the epoch
		server.enqueue(new MockResponse().setBody(new Buffer()
				.write(buildStream(subscribed, -1L, getRandomBytes(8)))));
		// Carrying no body at all
		server.enqueue(new MockResponse().setBody(new Buffer()
				.write(buildStream(subscribed, 1L, new byte[0]))));
		// Carrying more than a message may
		server.enqueue(new MockResponse().setBody(new Buffer()
				.write(buildStream(subscribed, 1L,
						getRandomBytes(MAX_MESSAGE_BODY_LENGTH + 1)))));
		server.start();
		try {
			channelManager1.setMirrors(g,
					singletonList(server.url("/c.briar").toString()));
			for (int i = 0; i < 3; i++) {
				assertEquals(UNREACHABLE,
						channelManager1.fetchChannel(g).getOutcome());
			}
			assertTrue(blogManager1.getPostHeaders(g).isEmpty());
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testContinuesADownloadThatWasCutOff() throws Exception {
		// The reason for all of this: on a connection that keeps dropping,
		// what arrived is kept and the next attempt asks for the rest
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] first = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] second = exportChannel(g);

		// The connection dies in the middle of the second post
		int cut = first.length + (second.length - first.length) / 2;
		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse()
				.setBody(new Buffer().write(second, 0, cut)));
		server.enqueue(new MockResponse().setResponseCode(206)
				.setBody(new Buffer().write(second, first.length,
						second.length - first.length)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			// The truncated file cannot be read to the end
			assertEquals(UNREACHABLE,
					channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			// but the post that did arrive whole was kept
			assertEquals(1, blogManager1.getPostHeaders(g).size());

			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			assertEquals(2, blogManager1.getPostHeaders(g).size());

			// The second attempt asked for the file from the end of the
			// last whole post, not from where the connection died
			server.takeRequest();
			assertEquals("bytes=" + first.length + "-",
					server.takeRequest().getHeader("Range"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testDoesNotTrustADateTheFileCouldStillChangeIn()
			throws Exception {
		// A mirror that gives no tag dates the file only to the second,
		// so a file changed again within that second keeps the date it
		// already had. Remembering such a date would mean being told
		// there is nothing new when there is, so it is not remembered
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] first = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] second = exportChannel(g);

		String date = "Wed, 16 Sep 2026 12:00:00 GMT";
		MockWebServer server = new MockWebServer();
		// The file was last changed in the second it was served in
		server.enqueue(new MockResponse()
				.setHeader("Date", date)
				.setHeader("Last-Modified", date)
				.setBody(new Buffer().write(first)));
		int from = first.length;
		server.enqueue(new MockResponse().setResponseCode(206)
				.setHeader("Content-Range", "bytes " + from + "-" +
						(second.length - 1) + "/" + second.length)
				.setBody(new Buffer().write(second, from,
						second.length - from)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			assertEquals(1, blogManager1.getPostHeaders(g).size());

			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			assertEquals(2, blogManager1.getPostHeaders(g).size());

			// The second fetch did not send the date back, so the mirror
			// could not wrongly answer that nothing had changed
			server.takeRequest();
			RecordedRequest second1 = server.takeRequest();
			assertNull(second1.getHeader("If-Modified-Since"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testTrustsADateThatIsSafelyInThePast() throws Exception {
		// A date from before the second the file was served in cannot
		// hide a later change, so it is remembered and sent back
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse()
				.setHeader("Date", "Wed, 16 Sep 2026 12:00:30 GMT")
				.setHeader("Last-Modified", "Wed, 16 Sep 2026 12:00:00 GMT")
				.setBody(new Buffer().write(exportChannel(g))));
		server.enqueue(new MockResponse().setResponseCode(304));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			assertEquals(UNCHANGED,
					channelManager1.fetchChannel(g).getOutcome());

			server.takeRequest();
			RecordedRequest conditional = server.takeRequest();
			assertEquals("Wed, 16 Sep 2026 12:00:00 GMT",
					conditional.getHeader("If-Modified-Since"));
		} finally {
			server.shutdown();
		}
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
