package org.briarproject.briar.channel;

import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.sync.Group.Visibility;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.util.Base32;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.test.TestDatabaseConfigModule;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogSharingManager;
import org.briarproject.briar.api.blog.BlogPost;
import org.briarproject.briar.api.blog.BlogPostHeader;
import org.briarproject.briar.api.messaging.ForwardedPost;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.attachment.CountingInputStream;
import org.briarproject.briar.api.channel.FetchResult;
import org.briarproject.briar.api.channel.NoSuchChannelException;
import org.briarproject.briar.test.BriarIntegrationTest;
import org.briarproject.briar.test.BriarIntegrationTestComponent;
import org.briarproject.briar.test.DaggerBriarIntegrationTestComponent;
import org.junit.Before;
import org.junit.Test;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static java.util.Collections.singletonList;
import static java.util.Locale.US;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.briarproject.bramble.api.sync.Group.Visibility.INVISIBLE;
import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.bramble.api.sync.validation.MessageState.DELIVERED;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.FETCHED;
import static org.briarproject.briar.channel.ChannelConstants.GROUP_KEY_FETCH_OFFSET;
import static org.briarproject.briar.channel.ChannelConstants.GROUP_KEY_MIRRORS;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_PREFIX;
import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_POST_TEXT_LENGTH;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_FETCH_BYTES_WITHOUT_NEWS;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_FORMAT_VERSION;
import static org.briarproject.briar.api.channel.ChannelConstants.FILE_STREAM_FORMAT_VERSION;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.NO_MIRRORS;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.NO_SAFE_MIRRORS;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.UNCHANGED;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.UNREACHABLE;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.IN_PROGRESS;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.TOO_LARGE;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.NOT_SUPPORTED;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.briar.api.attachment.MediaConstants.FILE_CHUNK_PAYLOAD_LENGTH;
import static org.briarproject.briar.attachment.ChunkedFileStore.KEY_FILE_MANIFEST_ID;
import static org.briarproject.bramble.util.StringUtils.getRandomString;
import static org.briarproject.briar.api.blog.MessageType.POST;
import static org.briarproject.briar.api.blog.MessageType.FILE_REQUEST;
import static org.briarproject.briar.api.blog.MessageType.ATTACHMENT;
import static org.briarproject.briar.api.blog.MessageType.FILE_CHUNK;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_STREAM_BYTES;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_STREAM_MESSAGES;
import static org.briarproject.briar.api.channel.ChannelConstants.MIN_HONEST_MESSAGE_BYTES;
import static org.briarproject.briar.api.channel.ChannelConstants.STREAM_FORMAT_VERSION;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_PUSHED_FILE_SIZE;
import static org.briarproject.bramble.util.StringUtils.toHexString;
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
	private long lastPostTime = 0;
	private BlogSharingManager blogSharingManager0, blogSharingManager1;

	@Before
	@Override
	public void setUp() throws Exception {
		super.setUp();
		channelManager0 = c0.getChannelManager();
		channelManager1 = c1.getChannelManager();
		blogManager0 = c0.getBlogManager();
		blogManager1 = c1.getBlogManager();
		blogSharingManager0 = c0.getBlogSharingManager();
		blogSharingManager1 = c1.getBlogSharingManager();
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
	public void testSharingWithContactsNeedsBothAndDoesNotLoop()
			throws Exception {
		// Two contacts who both hold a channel. Turning sharing on sends
		// each contact a private list of tokens, never an invitation, and
		// the channel becomes visible to a contact only once both have it
		// on, so nothing is offered to a contact who would drop it
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));

		// The owner turns it on: only the token list goes to the contact
		channelManager0.setSharingWithContacts(g, true);
		assertTrue(channelManager0.isSharingWithContacts(g));
		assertEquals(INVISIBLE, visibility(db0, contactId1From0, g));
		sync0To1(1, true);
		assertEquals(0, blogSharingManager0.getInvitations().size());
		assertEquals(0, blogSharingManager1.getInvitations().size());
		// However often they connect, the post is not offered to a
		// contact who has it off: this used to loop, re-sending the whole
		// channel every couple of minutes
		for (int i = 0; i < 5; i++) {
			assertNothingToSend(db0, contactId1From0);
			assertNothingToSend(db1, contactId0From1);
		}
		assertEquals(0, blogManager1.getPostHeaders(g).size());

		// The subscriber turns it on: their list goes back, and once it
		// arrives the post flows
		channelManager1.setSharingWithContacts(g, true);
		assertEquals(SHARED, visibility(db1, contactId0From1, g));
		sync1To0(1, true);
		assertEquals(SHARED, visibility(db0, contactId1From0, g));
		sync0To1(1, true);
		assertEquals(1, blogManager1.getPostHeaders(g).size());

		// The subscriber turns it off again: the owner stops offering
		channelManager1.setSharingWithContacts(g, false);
		assertEquals(INVISIBLE, visibility(db1, contactId0From1, g));
		sync1To0(1, true);
		assertEquals(INVISIBLE, visibility(db0, contactId1From0, g));
		channelManager0.post(g, getRandomString(42));
		assertNothingToSend(db0, contactId1From0);
	}

	@Test
	public void testChosenContactsOnlyGetThePosts() throws Exception {
		// Both have the switch on, so the post would flow; the owner then
		// chooses only another contact, and the channel is hidden from
		// this one until they are chosen too
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		channelManager0.setSharingWithContacts(g, true);
		channelManager1.setSharingWithContacts(g, true);
		assertNull(channelManager0.getSharingContacts(g));
		sync1To0(1, true);
		assertEquals(SHARED, visibility(db0, contactId1From0, g));

		channelManager0.setSharingContacts(g, singletonList(contactId2From0));
		assertEquals(singletonList(contactId2From0),
				channelManager0.getSharingContacts(g));
		assertEquals(INVISIBLE, visibility(db0, contactId1From0, g));
		// The contact's own list is told too: our token for them is gone
		sync0To1(1, true);
		assertNothingToSend(db0, contactId1From0);

		channelManager0.setSharingContacts(g,
				asList(contactId1From0, contactId2From0));
		assertEquals(SHARED, visibility(db0, contactId1From0, g));
	}

	@Test
	public void testAChannelIsNotSharedByInvitation() throws Exception {
		// An accepted invitation keeps a channel visible to the contact
		// whatever the switch says, so a contact who was ticked would keep
		// getting posts after being unticked. A channel can't be offered,
		// and the validator drops an invitation to one that arrives.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		assertEquals(NOT_SUPPORTED,
				blogSharingManager0.getSharingStatus(g, contact1From0));
		blogSharingManager0.sendInvitation(g, contactId1From0, null);
		assertNothingToSend(db0, contactId1From0);
		// A personal blog is shared by invitation as before
		Blog personal = blogManager0.getPersonalBlog(author1);
		assertNotEquals(NOT_SUPPORTED,
				blogSharingManager0.getSharingStatus(personal.getId(),
						contact1From0));
	}

	private Visibility visibility(DatabaseComponent db, ContactId c,
			GroupId g) throws DbException {
		return db.transactionWithResult(true,
				txn -> db.getGroupVisibility(txn, c, g));
	}

	private void assertNothingToSend(DatabaseComponent db, ContactId c)
			throws DbException {
		assertFalse(db.transactionWithResult(true, txn ->
				db.containsMessagesToSend(txn, c, Integer.MAX_VALUE, false)));
	}

	@Test
	public void testANearbyCopyIsReadOverAConnection() throws Exception {
		// Two phones holding the channel, connected by nothing but a
		// Bluetooth link keyed by the channel's key: one serves its copy
		// and the small files it has, the other reads and stores them
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] bytes = getRandomBytes(FILE_CHUNK_PAYLOAD_LENGTH + 5);
		FileHeader file = postFile(channel, bytes);
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));

		PipedInputStream serverIn = new PipedInputStream();
		PipedOutputStream readerOut = new PipedOutputStream(serverIn);
		PipedInputStream readerIn = new PipedInputStream();
		PipedOutputStream serverOut = new PipedOutputStream(readerIn);
		Thread server = new Thread(() -> {
			try {
				c0.getNearbyChannelProtocol().serve(g, serverIn, serverOut);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		server.start();
		int read = c1.getNearbyChannelProtocol().read(g, readerIn, readerOut);
		server.join(10_000);
		// The post, the file's post and its manifest
		assertEquals(3, read);
		awaitPendingMessageDelivery(5);
		assertEquals(2, blogManager1.getPostHeaders(g).size());
		assertTrue(blogManager1.getFileStatus(file).isComplete());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyAndClose(blogManager1.getFile(file), out);
		assertArrayEquals(bytes, out.toByteArray());
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

		assertEquals(0, channelManager1.importChannel(
				new ByteArrayInputStream(buildStream(subscribed,
						forged.getMessage().getTimestamp(),
						forged.getMessage().getBody()))));

		// It was read past, and never stored
		assertTrue(blogManager1.getPostHeaders(g).isEmpty());
		assertTrue(db1.transactionWithResult(true,
				txn -> db1.getMessageIds(txn, g)).isEmpty());
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

		assertEquals(1, channelManager1.subscribeFromFile(
				new ByteArrayInputStream(out.toByteArray()), g));
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

		assertEquals(0, channelManager1.subscribeFromFile(
				new ByteArrayInputStream(buildStream(blog,
						forged.getMessage().getTimestamp(),
						forged.getMessage().getBody())), blog.getId()));

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
		channelManager1.subscribeFromFile(
				new ByteArrayInputStream(fromOwner.toByteArray()), g);
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
	public void testFetchesSendAFixedUserAgent() throws Exception {
		// The HTTP client's own name and version would set Briar's readers
		// apart from a site's other visitors, and builds from each other
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		FileHeader file = postFile(channel, 1000);
		MockWebServer server = serveChannel(g, file);
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(2);
			fetchFileWhenFree(g, file.getManifestId());
			int n = server.getRequestCount();
			assertTrue(n >= 2);
			for (int i = 0; i < n; i++) {
				assertEquals(ChannelManagerImpl.FETCH_USER_AGENT,
						server.takeRequest().getHeader("User-Agent"));
			}
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testARedirectIsNotFollowed() throws Exception {
		// A mirror could send the reader on to plain HTTP, or from an
		// onion service to the open internet, with the request's tags
		// and range, so a redirect counts as a mirror that failed
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));

		MockWebServer elsewhere = new MockWebServer();
		elsewhere.enqueue(new MockResponse().setBody(
				new Buffer().write(exportChannel(g))));
		elsewhere.start();
		MockWebServer redirecting = new MockWebServer();
		redirecting.enqueue(new MockResponse().setResponseCode(302)
				.setHeader("Location", elsewhere.url("/c.briar")));
		redirecting.start();
		try {
			channelManager0.setMirrors(g,
					singletonList(redirecting.url("/c.briar").toString()));
			channelManager1.subscribeFromLink(
					channelManager0.getChannelLink(g));
			assertEquals(UNREACHABLE,
					channelManager1.fetchChannel(g).getOutcome());
			assertEquals(0, elsewhere.getRequestCount());
		} finally {
			redirecting.shutdown();
			elsewhere.shutdown();
		}
	}

	@Test
	public void testTriesTheNextMirrorWhenOneLagsBehind() throws Exception {
		// A mirror that has less of the file than we do may be lagging
		// behind the one we read from, so the next mirror is asked to
		// continue the file before it is taken to have been replaced,
		// which would mean reading the whole file again
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] one = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] two = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] three = exportChannel(g);

		// Mirrors are asked in a random order, so each answers the same
		// whenever it is asked. The lagging one has the second file and
		// then falls back to an older one that ends before where we are.
		AtomicInteger phase = new AtomicInteger(1);
		AtomicInteger fullFetches = new AtomicInteger();
		MockWebServer lagging = new MockWebServer();
		lagging.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				if (request.getHeader("Range") == null) {
					if (phase.get() == 2) fullFetches.incrementAndGet();
					return new MockResponse()
							.setBody(new Buffer().write(two));
				}
				return new MockResponse().setResponseCode(416)
						.setHeader("Content-Range", "bytes */" + one.length);
			}
		});
		lagging.start();
		MockWebServer current = new MockWebServer();
		current.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				byte[] file = phase.get() == 1 ? two : three;
				String range = request.getHeader("Range");
				if (range == null) {
					if (phase.get() == 2) fullFetches.incrementAndGet();
					return new MockResponse()
							.setBody(new Buffer().write(file));
				}
				if (!range.equals("bytes=" + two.length + "-"))
					return new MockResponse().setResponseCode(400);
				if (file == two) {
					return new MockResponse().setResponseCode(416)
							.setHeader("Content-Range", "bytes */" +
									two.length);
				}
				return new MockResponse().setResponseCode(206)
						.setHeader("Content-Range", "bytes " + two.length +
								"-" + (three.length - 1) + "/" + three.length)
						.setBody(new Buffer().write(three, two.length,
								three.length - two.length));
			}
		});
		current.start();
		try {
			channelManager0.setMirrors(g, asList(
					lagging.url("/c.briar").toString(),
					current.url("/c.briar").toString()));
			channelManager1.subscribeFromLink(
					channelManager0.getChannelLink(g));
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(2);

			phase.set(2);
			FetchResult result = channelManager1.fetchChannel(g);
			assertEquals(FETCHED, result.getOutcome());
			assertEquals(1, result.getMessages());
			awaitPendingMessageDelivery(1);
			assertEquals(3, blogManager1.getPostHeaders(g).size());
			// Neither mirror was asked for the whole file
			assertEquals(0, fullFetches.get());
		} finally {
			lagging.shutdown();
			current.shutdown();
		}
	}

	@Test
	public void testAMirrorWithExactlyWhatWeHaveIsUpToDate()
			throws Exception {
		// A mirror that gave no tag or date answers a request for the
		// rest of an unchanged file with 416, and its Content-Range says
		// the file ends where we stopped: nothing new, not a changed file
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] stream = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(stream)));
		server.enqueue(new MockResponse().setResponseCode(416)
				.setHeader("Content-Range", "bytes */" + stream.length));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			assertEquals(UNCHANGED,
					channelManager1.fetchChannel(g).getOutcome());
			assertEquals(2, server.getRequestCount());
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testACappedRangeIsNotTakenForTheWholeFile() throws Exception {
		// A mirror may serve less of a range than was asked for. If it
		// says how long the file is, the fetch is not complete until we
		// have that much, so the validators are not kept and the next
		// fetch asks for the rest rather than being told nothing changed
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] one = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] two = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] three = exportChannel(g);

		MockWebServer server = new MockWebServer();
		// No validators, so the next fetch asks for a range
		server.enqueue(new MockResponse().setBody(new Buffer().write(one)));
		// Asked for the rest, the mirror serves only the second post,
		// saying the file goes on past it
		server.enqueue(new MockResponse().setResponseCode(206)
				.setHeader("ETag", "\"three\"")
				.setHeader("Content-Range", "bytes " + one.length + "-" +
						(two.length - 1) + "/" + three.length)
				.setBody(new Buffer().write(two, one.length,
						two.length - one.length)));
		server.enqueue(new MockResponse().setResponseCode(206)
				.setHeader("ETag", "\"three\"")
				.setHeader("Content-Range", "bytes " + two.length + "-" +
						(three.length - 1) + "/" + three.length)
				.setBody(new Buffer().write(three, two.length,
						three.length - two.length)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(3);
			assertEquals(3, blogManager1.getPostHeaders(g).size());
			server.takeRequest();
			server.takeRequest();
			RecordedRequest third = server.takeRequest();
			assertNull(third.getHeader("If-None-Match"));
			assertEquals("bytes=" + two.length + "-",
					third.getHeader("Range"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testAFileAttachedToADraftStaysOutOfTheChannelFile()
			throws Exception {
		// A file is stored when it is picked, before the post that
		// carries it is written. Until a post names it, it is not the
		// channel's content, and putting it in the file would make the
		// file change in the middle when the draft is dropped
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] before = exportChannel(g);
		byte[] draftBytes = getRandomBytes(123);
		FileHeader draft = blogManager0.addLocalFile(g,
				c0.getClock().currentTimeMillis(), "draft.bin",
				"application/octet-stream",
				() -> new ByteArrayInputStream(draftBytes));
		assertArrayEquals(before, exportChannel(g));
		assertTrue(channelManager0.getCompleteFiles(g).isEmpty());
		// With files included, its chunks stay out too
		ByteArrayOutputStream withFiles = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, withFiles, true);
		assertArrayEquals(before, withFiles.toByteArray());
		// Once a post carries it, it is in
		FileHeader posted = postFile(channel, FILE_CHUNK_PAYLOAD_LENGTH);
		assertEquals(singletonList(posted.getManifestId()),
				new ArrayList<>(channelManager0.getCompleteFiles(g)));
		blogManager0.removeFile(draft);
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
			assertTrue(blogManager1.getPostHeaders(g).isEmpty());
			assertTrue(db1.transactionWithResult(true,
					txn -> db1.getMessageIds(txn, g)).isEmpty());
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
		assertEquals(0, channelManager1.importChannel(
				new ByteArrayInputStream(buildStream(subscribed,
						tampered.getTimestamp(), tampered.getBody()))));
		// Its signature is checked as it is read, so it is never stored
		assertFalse(isStored(tampered.getId()));
		assertTrue(blogManager1.getPostHeaders(g).isEmpty());
	}

	@Test
	public void testHoldsBackALargeFileUntilASubscriberAsks()
			throws Exception {
		// Between contacts, a channel's large file arrives as its manifest
		// alone, and follows when a subscriber asks for it
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		Blog subscribed = blogFactory
				.createChannelBlog(channel.getLocalAuthor());
		db1.transaction(false, txn -> blogManager1.addBlog(txn, subscribed));
		shareBothWays(g);

		byte[] fileBytes = getRandomBytes((int) MAX_PUSHED_FILE_SIZE + 1);
		int chunks = (int) ((fileBytes.length + FILE_CHUNK_PAYLOAD_LENGTH - 1)
				/ FILE_CHUNK_PAYLOAD_LENGTH);
		FileHeader file = blogManager0.addLocalFile(g,
				c0.getClock().currentTimeMillis(), "clip.mp4", "video/mp4",
				() -> new ByteArrayInputStream(fileBytes));
		BlogPost post = blogPostFactory.createBlogPost(g,
				c0.getClock().currentTimeMillis() + 1, null,
				channel.getLocalAuthor(), "Watch this", emptyList(),
				singletonList(file));
		blogManager0.addLocalPost(post);

		// The post and the manifest, and no chunks
		sync0To1(2, true);
		assertTrue(blogManager1.getFileStatus(file).isAwaitingRequest());

		blogManager1.requestFile(file);
		sync1To0(1, true);
		sync0To1(chunks, true);

		assertTrue(blogManager1.getFileStatus(file).isComplete());
		ByteArrayOutputStream read = new ByteArrayOutputStream();
		copyAndClose(blogManager1.getFile(file), read);
		assertArrayEquals(fileBytes, read.toByteArray());
	}

	@Test
	public void testTheChannelFileLeavesOutChunks() throws Exception {
		// The file a mirror serves carries the posts and the manifests of
		// their files, so a video can't fill the limit on what a reader
		// takes from mirrors over the channel's life
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		FileHeader file = postFile(channel, FILE_CHUNK_PAYLOAD_LENGTH * 2);

		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		assertEquals(2, channelManager1.importChannel(
				new ByteArrayInputStream(exportChannel(g))));
		awaitPendingMessageDelivery(2);

		FileStatus status = blogManager1.getFileStatus(file);
		assertTrue(status.isManifestReceived());
		assertEquals(0, status.getChunksReceived());
	}

	@Test
	public void testAMirrorServesEachFileSeparately() throws Exception {
		// A small file is fetched from its own file beside the channel's
		// as soon as its manifest arrives, without being asked for
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		byte[] fileBytes = getRandomBytes(FILE_CHUNK_PAYLOAD_LENGTH * 2 + 5);
		FileHeader file = postFile(channel, fileBytes);

		MockWebServer server = serveChannel(g, file);
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitComplete(file);

			ByteArrayOutputStream read = new ByteArrayOutputStream();
			copyAndClose(blogManager1.getFile(file), read);
			assertArrayEquals(fileBytes, read.toByteArray());
			assertEquals("/files/" + toHexString(
					file.getManifestId().getBytes()).toLowerCase(Locale.US)
					+ ".briar", findFileRequest(server));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testFetchesALargeFileFromAMirrorOnlyWhenAsked()
			throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		FileHeader file =
				postFile(channel, (int) MAX_PUSHED_FILE_SIZE + 1);

		MockWebServer server = serveChannel(g, file);
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(2);
			assertTrue(blogManager1.getFileStatus(file).isAwaitingRequest());

			// Asking for it fetches it from the mirror, as well as asking
			// any contacts who share the channel
			blogManager1.requestFile(file);
			awaitComplete(file);
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testRefusesAnotherFilesChunks() throws Exception {
		// A mirror serving the chunks of a different file at a file's
		// address gets nothing stored: every chunk must name the manifest
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		FileHeader wanted = postFile(channel, FILE_CHUNK_PAYLOAD_LENGTH);
		FileHeader other = postFile(channel, FILE_CHUNK_PAYLOAD_LENGTH);
		ByteArrayOutputStream otherFile = new ByteArrayOutputStream();
		channelManager0.exportChannelFile(g, other.getManifestId(),
				otherFile);
		byte[] main = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				if ("/c.briar".equals(request.getPath())) {
					return new MockResponse().setBody(new Buffer().write(main));
				}
				// Whatever file is asked for, the other file's chunks
				return new MockResponse().setBody(
						new Buffer().write(otherFile.toByteArray()));
			}
		});
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			channelManager1.fetchChannel(g);
			awaitPendingMessageDelivery(4);
			assertEquals(UNREACHABLE,
					fetchFileWhenFree(g, wanted.getManifestId()));
			assertEquals(0,
					blogManager1.getFileStatus(wanted).getChunksReceived());
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testRefusesChunksThatDontMatchTheManifest() throws Exception {
		// A mirror serving junk under the right manifest ID gets nothing
		// stored and is not asked again: chunks are checked against the
		// manifest before they are stored, not after
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		FileHeader file = postFile(channel, FILE_CHUNK_PAYLOAD_LENGTH + 1);
		long manifestTime = lastPostTime;
		ByteArrayOutputStream junk = new ByteArrayOutputStream();
		junk.write(c0.getClientHelper().toByteArray(BdfList.of(
				FILE_STREAM_FORMAT_VERSION, file.getManifestId())));
		for (int i = 0; i < 2; i++) {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			body.write(c0.getClientHelper().toByteArray(BdfList.of(
					FILE_CHUNK.getInt(), file.getManifestId(), i)));
			body.write(getRandomBytes(i == 0 ? FILE_CHUNK_PAYLOAD_LENGTH : 1));
			junk.write(c0.getClientHelper().toByteArray(BdfList.of(
					manifestTime + 1, body.toByteArray())));
		}
		byte[] main = exportChannel(g);
		MockWebServer server = new MockWebServer();
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				if ("/c.briar".equals(request.getPath())) {
					return new MockResponse().setBody(new Buffer().write(main));
				}
				return new MockResponse().setBody(
						new Buffer().write(junk.toByteArray()));
			}
		});
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			channelManager1.fetchChannel(g);
			awaitPendingMessageDelivery(2);
			assertEquals(UNREACHABLE,
					fetchFileWhenFree(g, file.getManifestId()));
			assertEquals(0,
					blogManager1.getFileStatus(file).getChunksReceived());
			// Nothing was stored, not even a chunk found invalid later
			assertEquals(2, db1.transactionWithResult(true,
					txn -> db1.getMessageIds(txn, g)).size());
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testALinkDoesNotReplaceTheMirrorsOfAChannelAlreadyHeld()
			throws Exception {
		// Anyone with a channel's public link can make one with the same
		// title and key and their own mirrors
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		subscribeWithMirror(g, "https://first.example/c.briar");
		channelManager0.setMirrors(g,
				singletonList("https://second.example/c.briar"));
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		assertEquals(singletonList("https://first.example/c.briar"),
				channelManager1.getMirrors(g));
		// The owner's own mirrors are safe from it too
		Author a = channel.getBlog().getAuthor();
		byte[] raw = c1.getClientHelper().toByteArray(BdfList.of(
				LINK_FORMAT_VERSION, a.getName(),
				a.getPublicKey().getEncoded(),
				BdfList.of("https://evil.example/c.briar")));
		channelManager0.subscribeFromLink(
				LINK_PREFIX + Base32.encode(raw).toLowerCase(US));
		assertEquals(singletonList("https://second.example/c.briar"),
				channelManager0.getMirrors(g));
	}

	@Test
	public void testSkipsAnImageNoPostCarries() throws Exception {
		// Images and manifests are unsigned, so a mirror could serve any
		// number of them. One that no post carries is not stored.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(c1.getClientHelper().toByteArray(
				BdfList.of(ATTACHMENT.getInt(), "image/jpeg")));
		body.write(getRandomBytes(1000));
		byte[] stream = buildStream(channel.getBlog(), 1234,
				body.toByteArray());
		channelManager1.importChannel(new ByteArrayInputStream(stream));
		assertTrue(db1.transactionWithResult(true,
				txn -> db1.getMessageIds(txn, g)).isEmpty());
		// One a post carries is, and the post comes first in the file
		FileHeader file = postFile(channel, FILE_CHUNK_PAYLOAD_LENGTH);
		assertEquals(2, channelManager1.importChannel(
				new ByteArrayInputStream(exportChannel(g))));
		awaitPendingMessageDelivery(2);
		assertTrue(blogManager1.getFileStatus(file).isManifestReceived());
	}

	@Test
	public void testAFileWeMostlyHaveIsReadToTheEnd() throws Exception {
		// A mirror that serves only what we have is left after a while,
		// but a file is read once, so its new posts after the part we
		// have must not be lost however much of it we already hold
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		int posts = (int) (MAX_FETCH_BYTES_WITHOUT_NEWS
				/ MAX_BLOG_POST_TEXT_LENGTH) + 2;
		for (int i = 0; i < posts; i++) {
			channelManager0.post(g, getRandomString(MAX_BLOG_POST_TEXT_LENGTH));
		}
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		assertEquals(posts, channelManager1.importChannel(
				new ByteArrayInputStream(exportChannel(g))));
		awaitPendingMessageDelivery(posts);
		MessageId last = channelManager0.post(g, "The newest post");
		channelManager1.importChannel(
				new ByteArrayInputStream(exportChannel(g)));
		awaitPendingMessageDelivery(1);
		assertTrue(db1.transactionWithResult(true,
				txn -> db1.getMessageIds(txn, g)).contains(last));
	}

	@Test
	public void testSkipsARequestInAStream() throws Exception {
		// The owner's file never carries requests; one from a mirror is
		// unsigned and would be kept for ever and passed to contacts
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		byte[] body = c1.getClientHelper().toByteArray(
				BdfList.of(FILE_REQUEST.getInt(), getRandomId()));
		byte[] stream = buildStream(channel.getBlog(), 1234, body);
		channelManager1.importChannel(new ByteArrayInputStream(stream));
		assertTrue(db1.transactionWithResult(true,
				txn -> db1.getMessageIds(txn, g)).isEmpty());
	}

	@Test
	public void testResumesAnAttachmentFileCutOffPartWay() throws Exception {
		// A fetch that dies in the middle of a chunk continues from the
		// end of the last chunk stored, rather than from the beginning
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		FileHeader file = postFile(channel, FILE_CHUNK_PAYLOAD_LENGTH * 2 + 7);
		ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
		channelManager0.exportChannelFile(g, file.getManifestId(), fileOut);
		byte[] whole = fileOut.toByteArray();
		// Where the first chunk's entry ends
		CountingInputStream counting = new CountingInputStream(
				new ByteArrayInputStream(whole), whole.length);
		BdfReader r = c1.getBdfReaderFactory().createReader(counting);
		r.readList();
		r.readList();
		int firstEntryEnd = (int) counting.getBytesRead();
		int cut = firstEntryEnd + 10;
		byte[] main = exportChannel(g);
		String path = "/" + channelManager0.getFilePath(file.getManifestId());
		AtomicInteger fileRequests = new AtomicInteger();

		MockWebServer server = new MockWebServer();
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				if ("/c.briar".equals(request.getPath())) {
					return new MockResponse().setBody(new Buffer().write(main));
				}
				if (!path.equals(request.getPath()))
					return new MockResponse().setResponseCode(404);
				if (fileRequests.getAndIncrement() == 0) {
					// Cut off in the middle of the second chunk
					return new MockResponse()
							.setBody(new Buffer().write(whole, 0, cut));
				}
				String range = request.getHeader("Range");
				if (range == null || !range.startsWith("bytes="))
					return new MockResponse().setResponseCode(500);
				int from = Integer.parseInt(range.substring(6,
						range.length() - 1));
				return new MockResponse().setResponseCode(206)
						.setHeader("Content-Range", "bytes " + from + "-" +
								(whole.length - 1) + "/" + whole.length)
						.setBody(new Buffer().write(whole, from,
								whole.length - from));
			}
		});
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			channelManager1.fetchChannel(g);
			awaitPendingMessageDelivery(2);
			// The manifest's arrival starts a fetch in the background,
			// which races with this one: whichever comes first is cut
			// off, and the next continues from the first chunk's end
			for (int i = 0; i < 3; i++) {
				FetchResult.Outcome outcome =
						fetchFileWhenFree(g, file.getManifestId());
				if (outcome == FETCHED || outcome == UNCHANGED) break;
			}
			awaitComplete(file);
			assertEquals(2, fileRequests.get());
			server.takeRequest();
			server.takeRequest();
			assertEquals("bytes=" + firstEntryEnd + "-",
					server.takeRequest().getHeader("Range"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testAFilePickedBeforeAnotherPostGoesAfterIt()
			throws Exception {
		// An image or file is stamped when it is picked, which may be
		// before a post written in between. Once its post is sent it must
		// go after that post in the file, or the file changes in the
		// middle and every reader starts again
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] bytes = getRandomBytes(FILE_CHUNK_PAYLOAD_LENGTH + 1);
		long pickTime = c0.getClock().currentTimeMillis();
		FileHeader picked = blogManager0.addLocalFile(g, pickTime, "a.bin",
				"application/octet-stream",
				() -> new ByteArrayInputStream(bytes));
		lastPostTime = pickTime + 100;
		channelManager0.post(g, getRandomString(42));
		byte[] before = exportChannel(g);
		// Timestamped as the app does, after everything in the channel
		BlogPost post = blogPostFactory.createBlogPost(g,
				blogManager0.getNextTimestamp(g, pickTime + 200),
				null, channel.getLocalAuthor(), "The file", emptyList(),
				singletonList(picked));
		blogManager0.addLocalPost(post);
		byte[] after = exportChannel(g);
		assertTrue(after.length > before.length);
		assertArrayEquals(before, Arrays.copyOf(after, before.length));
		ByteArrayOutputStream withFiles = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, withFiles, true);
		assertArrayEquals(before,
				Arrays.copyOf(withFiles.toByteArray(), before.length));
	}

	@Test
	public void testAMirrorServingAWebPageDoesNotForgetTheFile()
			throws Exception {
		// A static host answers a missing file with a page and status
		// 200. That says nothing about the channel's file, so what we
		// knew of it is kept, and the next mirror is asked for changes
		// rather than for the whole file
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] stream = exportChannel(g);

		MockWebServer good = new MockWebServer();
		good.enqueue(new MockResponse().setHeader("ETag", "\"one\"")
				.setBody(new Buffer().write(stream)));
		good.enqueue(new MockResponse().setResponseCode(304));
		good.start();
		MockWebServer bad = new MockWebServer();
		bad.enqueue(new MockResponse().setHeader("Content-Type", "text/html")
				.setBody("<html><body>Not found</body></html>"));
		bad.start();
		try {
			subscribeWithMirror(g, good.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			channelManager1.setMirrors(g, asList(
					bad.url("/c.briar").toString(),
					good.url("/c.briar").toString()));
			assertEquals(UNCHANGED,
					channelManager1.fetchChannel(g).getOutcome());
			good.takeRequest();
			assertEquals("\"one\"",
					good.takeRequest().getHeader("If-None-Match"));
		} finally {
			good.shutdown();
			bad.shutdown();
		}
	}

	@Test
	public void testAManifestNoPostNamesIsNotFetched() throws Exception {
		// A manifest is unsigned, so a contact passing the channel on can
		// add one. Until a signed post names it, it must not make the
		// reader ask the channel's mirrors for anything.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		MockWebServer server = new MockWebServer();
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			shareBothWays(g);
			FileHeader planted = blogManager0.addLocalFile(g,
					c0.getClock().currentTimeMillis(), "planted.bin",
					"application/octet-stream",
					() -> new ByteArrayInputStream(new byte[100]));
			db0.transaction(false, txn ->
					db0.setMessageShared(txn, planted.getManifestId()));
			sync0To1(1, true);
			assertNull(server.takeRequest(2, SECONDS));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testARequestForAFileNoPostNamesIsNotPassedOn()
			throws Exception {
		// A request is unsigned like a manifest, so a contact passing the
		// channel on can send one with a file no post names. Until a post
		// names the file, neither the request nor the file's chunks go
		// on to the others we share the channel with.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		shareBothWays(g);
		db0.transaction(false, txn ->
				db0.setGroupVisibility(txn, contactId2From0, g, SHARED));
		FileHeader planted = blogManager1.addLocalFile(g,
				c1.getClock().currentTimeMillis(), "planted.bin",
				"application/octet-stream",
				() -> new ByteArrayInputStream(
						new byte[FILE_CHUNK_PAYLOAD_LENGTH]));
		db1.transaction(false, txn -> {
			db1.setMessageShared(txn, planted.getManifestId());
			BdfDictionary query = BdfDictionary.of(new BdfEntry(
					KEY_FILE_MANIFEST_ID, planted.getManifestId()));
			for (MessageId id : c1.getClientHelper().getMessageIds(txn, g,
					query)) {
				db1.setMessageShared(txn, id);
			}
		});
		blogManager1.requestFile(planted);
		// Manifest, chunk and request
		sync1To0(3, true);
		assertTrue(blogManager0.getFileStatus(planted).isComplete());
		assertNothingToSend(db0, contactId2From0);

		// Once the owner's post names the file, all three go on: a third
		// phone holding the channel gets the post, the whole file and the
		// request
		ChannelManager channelManager2 = c2.getChannelManager();
		channelManager2.subscribeFromLink(channelManager0.getChannelLink(g));
		db2.transaction(false, txn ->
				db2.setGroupVisibility(txn, contactId0From2, g, SHARED));
		BlogPost post = blogPostFactory.createBlogPost(g,
				c0.getClock().currentTimeMillis(), null,
				channel.getLocalAuthor(), "Now it is mine", emptyList(),
				singletonList(planted));
		blogManager0.addLocalPost(post);
		// Post, manifest, chunk and request
		sync0To2(4, true);
		assertTrue(c2.getBlogManager().getFileStatus(planted).isComplete());
		int chunkAndRequest = db2.transactionWithResult(true, txn ->
				c2.getClientHelper().getMessageIds(txn, g,
						BdfDictionary.of(new BdfEntry(KEY_FILE_MANIFEST_ID,
								planted.getManifestId()))).size());
		assertEquals(2, chunkAndRequest);
	}

	@Test
	public void testARequestKeptBackGoesOnOnceItsFileArrives()
			throws Exception {
		// Messages arrive in any order. A request that reaches a phone
		// before the file it names is kept back, since nothing names the
		// file yet. If the post naming the file arrives next and the
		// manifest last, the request must still go on from there, or the
		// phone holding the file never hears that it's wanted.
		addContacts1And2();
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		String link = channelManager0.getChannelLink(g);
		channelManager1.subscribeFromLink(link);
		c2.getChannelManager().subscribeFromLink(link);
		shareBothWays(g);
		db0.transaction(false, txn ->
				db0.setGroupVisibility(txn, contactId2From0, g, SHARED));
		db2.transaction(false, txn -> {
			db2.setGroupVisibility(txn, contactId0From2, g, SHARED);
			db2.setGroupVisibility(txn, contactId1From2, g, SHARED);
		});
		db1.transaction(false, txn ->
				db1.setGroupVisibility(txn, contactId2From1, g, SHARED));

		// The owner posts a file
		FileHeader file = blogManager0.addLocalFile(g,
				c0.getClock().currentTimeMillis(), "file.bin",
				"application/octet-stream",
				() -> new ByteArrayInputStream(
						new byte[FILE_CHUNK_PAYLOAD_LENGTH]));
		BlogPost post = blogPostFactory.createBlogPost(g,
				c0.getClock().currentTimeMillis(), null,
				channel.getLocalAuthor(), "A file", emptyList(),
				singletonList(file));
		blogManager0.addLocalPost(post);
		MessageId postId = post.getMessage().getId();
		MessageId manifestId = file.getManifestId();
		BdfDictionary naming = BdfDictionary.of(
				new BdfEntry(KEY_FILE_MANIFEST_ID, manifestId));

		// Phone 2 gets the manifest alone and asks for the file
		db0.transaction(false, txn -> {
			db0.setMessageNotShared(txn, postId);
			for (MessageId id : c0.getClientHelper().getMessageIds(txn, g,
					naming)) {
				db0.setMessageNotShared(txn, id);
			}
		});
		sync0To2(1, true);
		c2.getBlogManager().requestFile(file);

		// Phone 1 gets the post, then phone 2's request, and keeps the
		// request back, since the file it names isn't here
		db0.transaction(false, txn -> {
			db0.setMessageNotShared(txn, manifestId);
			db0.setMessageShared(txn, postId);
		});
		sync0To1(1, true);
		sync2To1(1, true);
		assertNothingToSend(db1, contactId0From1);

		// When the manifest arrives, the post already names it, so the
		// request goes on to the owner, who releases the file's chunks
		db0.transaction(false, txn -> db0.setMessageShared(txn, manifestId));
		sync0To1(1, true);
		sync1To0(1, true);
		assertTrue(db0.transactionWithResult(true, txn ->
				db0.containsMessagesToSend(txn, contactId1From0,
						Integer.MAX_VALUE, false)));
	}

	@Test
	public void testNothingAReaderGetsNamesTheOwner() throws Exception {
		// A channel exists so that its publisher is a key pair rather than
		// a person. Its file, which every reader and mirror gets, and its
		// link, which anyone can pass on, must not carry the owner's name,
		// identity key or author ID, even with posts, images and files.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		postFile(channel, FILE_CHUNK_PAYLOAD_LENGTH + 1);
		channelManager0.setMirrors(g,
				singletonList("https://mirror.example/c.briar"));
		byte[] file = exportChannel(g);
		byte[] link = channelManager0.getChannelLink(g).getBytes(UTF_8);
		byte[][] owner = {author0.getPublicKey().getEncoded(),
				author0.getId().getBytes(), author0.getName().getBytes(UTF_8)};
		for (byte[] o : owner) {
			assertFalse(contains(file, o));
			assertFalse(contains(link, o));
		}
		// The channel's own key is what the file is signed by
		assertTrue(contains(file, channel.getLocalAuthor().getPublicKey()
				.getEncoded()));
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	@Test
	public void testAnOwnerDoesNotFetchTheirOwnChannel() throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		MockWebServer server = new MockWebServer();
		server.start();
		try {
			channelManager0.setMirrors(g,
					singletonList(server.url("/c.briar").toString()));
			channelManager0.fetchAllChannels();
			assertEquals(0, server.getRequestCount());
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testALinkGivesMirrorsToAChannelHeldFromAFile()
			throws Exception {
		// A channel that came from a file has nowhere to fetch from until
		// a link arrives
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		channelManager1.subscribeFromFile(
				new ByteArrayInputStream(exportChannel(g)), g);
		assertTrue(channelManager1.getMirrors(g).isEmpty());
		channelManager0.setMirrors(g,
				singletonList("https://mirror.example/c.briar"));
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		assertEquals(singletonList("https://mirror.example/c.briar"),
				channelManager1.getMirrors(g));
	}

	@Test
	public void testAHandedOverFileCanCarryItsFiles() throws Exception {
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		FileHeader file = postFile(channel, FILE_CHUNK_PAYLOAD_LENGTH * 3);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, out, true);

		assertEquals(5, channelManager1.subscribeFromFile(
				new ByteArrayInputStream(out.toByteArray()), g));
		awaitPendingMessageDelivery(5);
		assertTrue(blogManager1.getFileStatus(file).isComplete());
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
		// manifest and both chunks from a file handed over with its files
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		channelManager0.exportChannel(g, out, true);
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
		// A channel's timestamps say only which minute, so each is
		// somewhere in the minute of the moment asked for
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		long moment = c0.getClock().currentTimeMillis();
		long minute = moment - moment % 60_000;
		long first = blogManager0.getNextTimestamp(g, moment);
		assertTrue(first >= minute && first < minute + 60_000);

		// Something written takes its timestamp, so the next message is
		// put after it, however many are written in the same minute
		AttachmentHeader a = blogManager0.addLocalAttachment(g, moment,
				"image/jpeg", new ByteArrayInputStream(getRandomBytes(123)));
		long taken = db0.transactionWithResult(true,
				txn -> db0.getMessage(txn, a.getMessageId())).getTimestamp();
		long next = blogManager0.getNextTimestamp(g, moment);
		assertTrue(next > taken);
		AttachmentHeader b = blogManager0.addLocalAttachment(g, moment,
				"image/jpeg", new ByteArrayInputStream(getRandomBytes(123)));
		long taken2 = db0.transactionWithResult(true,
				txn -> db0.getMessage(txn, b.getMessageId())).getTimestamp();
		assertTrue(taken2 > taken);
		assertTrue(blogManager0.getNextTimestamp(g, moment) > taken2);

		// A moment minutes after everything in the channel is in its own
		// minute
		long later = moment + 5 * 60_000;
		long inLater = blogManager0.getNextTimestamp(g, later);
		assertEquals(later - later % 60_000, inLater - inLater % 60_000);

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
	public void testAMirrorWithholdingAFileIsNotTheOnlyOneAsked()
			throws Exception {
		// A mirror answers a file with its header and no chunks. Files
		// used to be asked of the mirrors in link order, taking the first
		// answer, so the first mirror could withhold every file from
		// every reader, and saw every file every reader asked for.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		FileHeader file = postFile(channel, 1000);
		byte[] main = exportChannel(g);
		ByteArrayOutputStream fileOut = new ByteArrayOutputStream();
		channelManager0.exportChannelFile(g, file.getManifestId(), fileOut);
		byte[] whole = fileOut.toByteArray();
		CountingInputStream counting = new CountingInputStream(
				new ByteArrayInputStream(whole), whole.length);
		c1.getBdfReaderFactory().createReader(counting).readList();
		byte[] headerOnly = Arrays.copyOf(whole, (int) counting.getBytesRead());
		String path = "/" + channelManager0.getFilePath(file.getManifestId());

		MockWebServer withholding = new MockWebServer();
		withholding.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				byte[] body = "/c.briar".equals(request.getPath())
						? main : headerOnly;
				return new MockResponse().setBody(new Buffer().write(body));
			}
		});
		withholding.start();
		MockWebServer honest = serveChannel(g, file);
		try {
			channelManager0.setMirrors(g, asList(
					withholding.url("/c.briar").toString(),
					honest.url("/c.briar").toString()));
			channelManager1.subscribeFromLink(
					channelManager0.getChannelLink(g));
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(2);
			FetchResult.Outcome o = fetchFileWhenFree(g, file.getManifestId());
			assertTrue(o.toString(), o == FETCHED || o == UNCHANGED);
			// The chunks read are delivered in the background
			awaitComplete(file);
			// Whichever was asked first, the honest mirror served the file
			List<String> asked = new ArrayList<>();
			int n = honest.getRequestCount();
			for (int i = 0; i < n; i++) asked.add(honest.takeRequest().getPath());
			assertTrue(asked.contains(path));
		} finally {
			withholding.shutdown();
			honest.shutdown();
		}
	}

	@Test
	public void testAFileIsNotFetchedFromAPlainHttpMirror() throws Exception {
		// A link may name plain HTTP mirrors, which are kept so the link
		// can be passed on, but never fetched from: an exit could read
		// and change what is fetched. That held for the channel's file,
		// but not for its attachments.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		FileHeader file = postFile(channel, 1000);
		Author a = channel.getBlog().getAuthor();
		byte[] raw = c1.getClientHelper().toByteArray(BdfList.of(
				LINK_FORMAT_VERSION, a.getName(), a.getPublicKey().getEncoded(),
				BdfList.of("http://mirror.example/c.briar")));
		channelManager1.subscribeFromLink(
				LINK_PREFIX + Base32.encode(raw).toLowerCase(US));
		// The channel's file carries the post and the manifest, not the
		// file's chunks, so the file is there to be fetched
		channelManager1.importChannel(new ByteArrayInputStream(exportChannel(g)));
		awaitPendingMessageDelivery(2);
		assertFalse(blogManager1.getFileStatus(file).isComplete());
		assertEquals(NO_SAFE_MIRRORS,
				fetchFileWhenFree(g, file.getManifestId()));
	}

	@Test
	public void testAMirrorThatIgnoresRangesAndHasNothingNewIsUnchanged()
			throws Exception {
		// A mirror that answers 200 with the file we already have, and
		// nothing after it, has told us there is nothing new. It doesn't
		// count as serving the channel, so another mirror is asked.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] first = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(first)));
		server.enqueue(new MockResponse().setBody(new Buffer().write(first)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);

			assertEquals(UNCHANGED,
					channelManager1.fetchChannel(g).getOutcome());
			server.takeRequest();
			assertEquals("bytes=" + first.length + "-",
					server.takeRequest().getHeader("Range"));
			assertEquals(first.length, fetchOffset(g));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testAMirrorCannotPadTheFileWithoutLimit() throws Exception {
		// A mirror ignores the range and serves the file we have with
		// megabytes of posts the channel didn't sign after it. Before,
		// the whole answer was read and counted as a fresh fetch, so a
		// mirror could make a phone download that much whenever it
		// liked. Now reading stops soon after the junk starts.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] first = exportChannel(g);
		ByteArrayOutputStream padded = new ByteArrayOutputStream();
		padded.write(first);
		ClientHelper h = c1.getClientHelper();
		long time = c0.getClock().currentTimeMillis();
		while (padded.size() < first.length + 3 * 1024 * 1024) {
			byte[] forged = h.toByteArray(BdfList.of(POST.getInt(),
					getRandomString(30_000), getRandomBytes(64)));
			padded.write(h.toByteArray(BdfList.of(time++, forged)));
		}

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(first)));
		server.enqueue(new MockResponse().setBody(
				new Buffer().write(padded.toByteArray())));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);

			// A padded answer is a mirror that failed, and the junk is
			// never counted as part of the file
			assertEquals(UNREACHABLE,
					channelManager1.fetchChannel(g).getOutcome());
			assertEquals(first.length, fetchOffset(g));
			assertEquals(1, blogManager1.getPostHeaders(g).size());
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testStartsAgainWhenAMirrorIgnoringRangesServesAnotherFile()
			throws Exception {
		// A mirror ignores the range and serves a file in which no entry
		// ends where our part did: not the file we were reading, so it
		// is read from the beginning like any other changed file
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] first = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] second = exportChannel(g);
		// The second file with an entry slipped in after its header
		CountingInputStream counting = new CountingInputStream(
				new ByteArrayInputStream(second), second.length);
		c1.getBdfReaderFactory().createReader(counting).readList();
		int headerEnd = (int) counting.getBytesRead();
		ClientHelper h = c1.getClientHelper();
		ByteArrayOutputStream other = new ByteArrayOutputStream();
		other.write(second, 0, headerEnd);
		other.write(h.toByteArray(BdfList.of(1L, getRandomBytes(10))));
		other.write(second, headerEnd, second.length - headerEnd);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(first)));
		server.enqueue(new MockResponse().setBody(
				new Buffer().write(other.toByteArray())));
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
			assertEquals("bytes=" + first.length + "-",
					server.takeRequest().getHeader("Range"));
			assertNull(server.takeRequest().getHeader("Range"));
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testStartsAgainWhenAMirrorIgnoringRangesServesAShorterFile()
			throws Exception {
		// The mirror says how long the file is, and it is shorter than
		// the part we have: not the file we were reading
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] shorter = exportChannel(g);
		channelManager0.post(g, getRandomString(42));
		byte[] longer = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(longer)));
		server.enqueue(new MockResponse().setBody(new Buffer().write(shorter)));
		server.enqueue(new MockResponse().setBody(new Buffer().write(shorter)));
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(2);

			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			server.takeRequest();
			assertEquals("bytes=" + longer.length + "-",
					server.takeRequest().getHeader("Range"));
			assertNull(server.takeRequest().getHeader("Range"));
			assertEquals(shorter.length, fetchOffset(g));
		} finally {
			server.shutdown();
		}
	}

	private long fetchOffset(GroupId g) throws Exception {
		return db1.transactionWithResult(true, txn -> c1.getClientHelper()
				.getGroupMetadataAsDictionary(txn, g)
				.getLong(GROUP_KEY_FETCH_OFFSET, 0L));
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

		assertEquals(posts, channelManager1.subscribeFromFile(
				new ByteArrayInputStream(out.toByteArray()), g));
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
		// Only the channel's own messages count: junk is read past and
		// never counted, so the file is filled with real posts
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager1.subscribe("Announcements",
				channel.getLocalAuthor().getPublicKey());
		// Posts of one size, to get near the limit without exporting
		// the file after each; then exactly up to it, exporting each time
		String text = new String(new char[30_000]).replace('\0', 'a');
		channelManager0.post(g, text);
		int one = exportChannel(g).length;
		channelManager0.post(g, text);
		int entry = exportChannel(g).length - one;
		for (long size = one + entry; size + 3L * entry <= MAX_STREAM_BYTES;
				size += entry) {
			channelManager0.post(g, text);
		}
		byte[] head = exportChannel(g), whole;
		while (true) {
			channelManager0.post(g, text);
			whole = exportChannel(g);
			if (whole.length > MAX_STREAM_BYTES) break;
			head = whole;
		}
		assertTrue(head.length <= MAX_STREAM_BYTES);
		byte[] tail = new byte[whole.length - head.length];
		System.arraycopy(whole, head.length, tail, 0, tail.length);

		MockWebServer server = new MockWebServer();
		// As much as a file may carry, which is allowed
		server.enqueue(new MockResponse().setBody(new Buffer().write(head)));
		// One more message, which is not, however it is spread over
		// fetches
		server.enqueue(new MockResponse().setResponseCode(206)
				.setBody(new Buffer().write(tail)));
		server.start();
		try {
			channelManager1.setMirrors(g,
					singletonList(server.url("/c.briar").toString()));
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			// The file is reported too large rather than read again:
			// there is nothing wrong with it, we just won't store it.
			// The budget runs out in the middle of the extra message,
			// which must not be taken for a file that has changed, or
			// the whole file would be fetched again every time.
			assertEquals(TOO_LARGE,
					channelManager1.fetchChannel(g).getOutcome());

			assertEquals(2, server.getRequestCount());
			server.takeRequest();
			assertEquals("bytes=" + head.length + "-",
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

	private FileHeader postFile(Channel channel, int size) throws Exception {
		return postFile(channel, getRandomBytes(size));
	}

	private FileHeader postFile(Channel channel, byte[] bytes)
			throws Exception {
		GroupId g = channel.getBlogId();
		long now = c0.getClock().currentTimeMillis();
		long time = Math.max(now, lastPostTime + 10);
		lastPostTime = time;
		FileHeader file = blogManager0.addLocalFile(g, time, "clip.bin",
				"application/octet-stream",
				() -> new ByteArrayInputStream(bytes));
		BlogPost post = blogPostFactory.createBlogPost(g, time + 2, null,
				channel.getLocalAuthor(), "A file", emptyList(),
				singletonList(file));
		blogManager0.addLocalPost(post);
		return file;
	}

	/**
	 * Serves a channel's file at /c.briar and each of the given files at
	 * its own path beside it, as a mirror would after the owner publishes.
	 */
	private MockWebServer serveChannel(GroupId g, FileHeader... files)
			throws Exception {
		byte[] main = exportChannel(g);
		Map<String, byte[]> served = new HashMap<>();
		for (FileHeader f : files) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			channelManager0.exportChannelFile(g, f.getManifestId(), out);
			served.put("/" + channelManager0.getFilePath(f.getManifestId()),
					out.toByteArray());
		}
		MockWebServer server = new MockWebServer();
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				if ("/c.briar".equals(request.getPath())) {
					return new MockResponse().setBody(new Buffer().write(main));
				}
				byte[] file = served.get(request.getPath());
				if (file == null) return new MockResponse().setResponseCode(404);
				return new MockResponse().setBody(new Buffer().write(file));
			}
		});
		server.start();
		return server;
	}

	/**
	 * Fetches a file, waiting for the fetch a manifest's arrival starts
	 * in the background to finish first.
	 */
	private FetchResult.Outcome fetchFileWhenFree(GroupId g,
			MessageId manifestId) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (true) {
			FetchResult.Outcome outcome =
					channelManager1.fetchChannelFile(g, manifestId).getOutcome();
			if (outcome != IN_PROGRESS) return outcome;
			if (System.currentTimeMillis() > deadline)
				throw new AssertionError("Fetch did not finish");
			Thread.sleep(50);
		}
	}

	private String findFileRequest(MockWebServer server) throws Exception {
		for (int i = 0; i < server.getRequestCount(); i++) {
			String path = server.takeRequest().getPath();
			if (path != null && path.startsWith("/files/")) return path;
		}
		throw new AssertionError("No file was fetched");
	}

	/**
	 * Waits for a file fetched in the background to be complete.
	 */
	private void awaitComplete(FileHeader file) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (!blogManager1.getFileStatus(file).isComplete()) {
			if (System.currentTimeMillis() > deadline)
				throw new AssertionError("File did not arrive");
			Thread.sleep(100);
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

	@Test
	public void testRefusesAMirrorThatIsNotAnHttpUrl() throws Exception {
		// Anything the HTTP client can't parse would throw on the fetch
		// thread at every scheduled fetch, so it is refused at the door
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		try {
			channelManager0.setMirrors(g, singletonList("ftp://x/c.briar"));
			fail();
		} catch (IllegalArgumentException expected) {
			// Expected
		}
		assertTrue(channelManager0.getMirrors(g).isEmpty());
		// A link carrying such a mirror is malformed
		Author a = channel.getBlog().getAuthor();
		byte[] raw = c1.getClientHelper().toByteArray(BdfList.of(
				LINK_FORMAT_VERSION, a.getName(),
				a.getPublicKey().getEncoded(), BdfList.of("x")));
		expectBadLink(LINK_PREFIX + Base32.encode(raw).toLowerCase(US));
		// A mirror stored before the check is unreachable, not a crash
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		BdfDictionary meta = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_MIRRORS, BdfList.of("x")));
		db1.transaction(false, txn ->
				c1.getClientHelper().mergeGroupMetadata(txn, g, meta));
		assertEquals(NO_SAFE_MIRRORS,
				channelManager1.fetchChannel(g).getOutcome());
	}

	@Test
	public void testForgetsATagItCouldNotSendBack() throws Exception {
		// The HTTP client refuses a header value outside printable ASCII,
		// so a tag like that, sent back, would make every later fetch
		// of the channel throw
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] stream = exportChannel(g);

		MockWebServer server = new MockWebServer();
		server.enqueue(new MockResponse().setBody(new Buffer().write(stream))
				.addHeaderLenient("ETag", "\"v1\u00e9\""));
		server.enqueue(new MockResponse().setBody(new Buffer().write(stream))
				.addHeader("ETag", "\"v2\""));
		server.enqueue(new MockResponse().setResponseCode(304));
		server.start();
		try {
			channelManager0.setMirrors(g,
					singletonList(server.url("/c.briar").toString()));
			channelManager1.subscribeFromLink(
					channelManager0.getChannelLink(g));
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			server.takeRequest();
			// The bad tag was not kept, so the next fetch sends none. The
			// mirror serves the same file again, which is nothing new
			assertEquals(UNCHANGED,
					channelManager1.fetchChannel(g).getOutcome());
			assertNull(server.takeRequest().getHeader("If-None-Match"));
			// A good tag is kept and sent
			assertEquals(UNCHANGED,
					channelManager1.fetchChannel(g).getOutcome());
			assertEquals("\"v2\"", server.takeRequest()
					.getHeader("If-None-Match"));
		} finally {
			server.shutdown();
		}
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
	@Test
	public void testAnIdOfTheWrongLengthInAStreamIsSkipped()
			throws Exception {
		// A mirror or a file can put any bytes where an ID belongs. One
		// of the wrong length must be skipped, not crash the import.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		ClientHelper h = c1.getClientHelper();
		ByteArrayOutputStream chunk = new ByteArrayOutputStream();
		chunk.write(h.toByteArray(
				BdfList.of(FILE_CHUNK.getInt(), getRandomBytes(31), 0)));
		chunk.write(getRandomBytes(100));
		byte[] post = h.toByteArray(BdfList.of(POST.getInt(), "Hi",
				BdfList.of(BdfList.of(getRandomBytes(31), "image/jpeg")),
				getRandomBytes(64)));
		Message m1 = messageFactory.createMessage(g, 1234,
				chunk.toByteArray());
		Message m2 = messageFactory.createMessage(g, 1235, post);
		// Neither is the channel's, so neither is read as a message
		assertEquals(0, channelManager1.importChannel(
				new ByteArrayInputStream(
						buildStream(channel.getBlog(), m1, m2))));
		assertFalse(isStored(m1.getId()));
		assertFalse(isStored(m2.getId()));
	}

	@Test(expected = FormatException.class)
	public void testANearbyFileIdOfTheWrongLengthIsMalformed()
			throws Exception {
		// The phone serving a nearby copy is a stranger, so a file ID
		// of the wrong length is a malformed exchange, not a crash
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		ClientHelper h = c1.getClientHelper();
		byte[] copy = exportChannel(g);
		ByteArrayOutputStream served = new ByteArrayOutputStream();
		served.write(h.toByteArray(BdfList.of((long) copy.length)));
		served.write(copy);
		served.write(h.toByteArray(BdfList.of(getRandomBytes(31))));
		c1.getNearbyChannelProtocol().read(g,
				new ByteArrayInputStream(served.toByteArray()),
				new ByteArrayOutputStream());
	}

	@Test
	public void testAChannelsTimestampsAreKeptToTheMinute() throws Exception {
		// A post's timestamp is published and signed. To the millisecond
		// it would show how far the owner's clock is off, so it says only
		// which minute, at a random point in it, and still after every
		// message already in the channel.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		long before = c0.getClock().currentTimeMillis();
		long last = 0;
		Set<Long> withinMinute = new HashSet<>();
		for (int i = 0; i < 5; i++) {
			MessageId id = channelManager0.post(g, getRandomString(42));
			long t = db0.transactionWithResult(true,
					txn -> db0.getMessage(txn, id)).getTimestamp();
			long after = c0.getClock().currentTimeMillis();
			assertTrue(t > last);
			assertTrue(t >= before - before % 60_000);
			assertTrue(t < after - after % 60_000 + 60_000 + 5);
			withinMinute.add(t % 60_000);
			last = t;
		}
		// Not the clock's milliseconds: the posts were made within
		// milliseconds of each other, but land across the minute
		assertTrue(withinMinute.size() > 1);
	}

	@Test
	public void testAForwardedPostIsCheckedAndSeedsTheChannel()
			throws Exception {
		// A forward carries the post as the channel signed it, so the
		// recipient's device checks the text is the channel's, and their
		// copy of the channel holds the post from the moment they subscribe
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		String text = "<b>Bread</b> at the corner shop";
		MessageId postId = channelManager0.post(g, text);
		ForwardedPost post = channelManager0.getForwardedPost(g, postId);
		assertEquals(text, post.getText());
		// The contact's client is this one, so it can take a forward
		assertTrue(db0.transactionWithResult(true, txn ->
				c0.getMessagingManager().getContactMessageFormat(txn,
						contactId1From0)).supportsForwarding());
		assertEquals(channelManager0.getChannelLink(g), post.getChannelLink());

		PrivateMessage m = forward(post);
		sync0To1(1, true);

		// The recipient's device checked the signature on arrival
		ForwardedPost received = c1.getMessagingManager()
				.getForwardedPost(m.getMessage().getId());
		assertEquals(text, received.getText());
		assertEquals(post.getChannelLink(), received.getChannelLink());
		// Both sides' conversations show it as a forward
		assertEquals(post.getChannelLink(), forwardHeader(c0,
				contactId1From0, m.getMessage().getId()).getChannelLink());
		assertEquals(post.getChannelLink(), forwardHeader(c1,
				contactId0From1, m.getMessage().getId()).getChannelLink());
		assertFalse(db1.transactionWithResult(true,
				txn -> db1.containsGroup(txn, g)));

		Blog blog = channelManager1.subscribeFromForward(received);
		assertEquals(g, blog.getId());
		awaitPendingMessageDelivery(1);
		Collection<BlogPostHeader> headers = blogManager1.getPostHeaders(g);
		assertEquals(1, headers.size());
		assertEquals(postId, headers.iterator().next().getId());
		assertEquals(text, blogManager1.getPostText(postId));
	}

	@Test
	public void testAForwardWithChangedTextIsRefused() throws Exception {
		// A contact changes a post's text and forwards it with the real
		// channel's link and signature: the recipient's device refuses the
		// message, so nothing is shown under the channel's name
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		MessageId postId = channelManager0.post(g, "Bread at the corner shop");
		ForwardedPost post = channelManager0.getForwardedPost(g, postId);
		ForwardedPost forged = new ForwardedPost(post.getChannelLink(),
				post.getTimestamp(), "The corner shop has closed",
				post.getAttachmentHeaders(), post.getSignature());
		forward(forged);
		syncMessage(c0, c1, contactId1From0, 1, 0, 1, 0);
	}

	private PrivateMessageHeader forwardHeader(BriarIntegrationTestComponent c,
			ContactId contact, MessageId id) throws Exception {
		for (ConversationMessageHeader h : c.getConversationManager()
				.getMessageHeaders(contact)) {
			if (h.getId().equals(id)) return (PrivateMessageHeader) h;
		}
		throw new AssertionError("No such message");
	}

	private PrivateMessage forward(ForwardedPost post) throws Exception {
		MessagingManager messaging0 = c0.getMessagingManager();
		GroupId conversation = messaging0.getConversationId(contactId1From0);
		PrivateMessage m = c0.getPrivateMessageFactory().createForwardedPost(
				conversation, c0.getClock().currentTimeMillis(),
				NO_AUTO_DELETE_TIMER, post);
		messaging0.addLocalMessage(m);
		return m;
	}

	@Test
	public void testANearbyCopyOfAnotherChannelIsRefused() throws Exception {
		// A stranger met while sharing one channel serves another channel
		// we hold, to push posts into it or to learn that we hold it
		Channel shared = channelManager0.createChannel("Announcements");
		Channel other = channelManager0.createChannel("News");
		GroupId g = shared.getBlogId();
		GroupId otherId = other.getBlogId();
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		channelManager1.subscribeFromLink(
				channelManager0.getChannelLink(otherId));
		channelManager0.post(otherId, getRandomString(42));
		ClientHelper h = c1.getClientHelper();
		byte[] copy = exportChannel(otherId);
		ByteArrayOutputStream served = new ByteArrayOutputStream();
		served.write(h.toByteArray(BdfList.of((long) copy.length)));
		served.write(copy);
		served.write(h.toByteArray(new BdfList()));
		try {
			c1.getNearbyChannelProtocol().read(g,
					new ByteArrayInputStream(served.toByteArray()),
					new ByteArrayOutputStream());
			fail();
		} catch (FormatException expected) {
			// The copy must be of the channel being shared
		}
	}

	@Test
	public void testAForgedPostCannotGetAnImageStored() throws Exception {
		// Images are unsigned, and are stored only if a post carries
		// them. A post that isn't the owner's must not count, even
		// though it is rejected later.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(c1.getClientHelper().toByteArray(
				BdfList.of(ATTACHMENT.getInt(), "image/jpeg")));
		body.write(getRandomBytes(1000));
		Message image = messageFactory.createMessage(g, 1234,
				body.toByteArray());
		AttachmentHeader header =
				new AttachmentHeader(g, image.getId(), "image/jpeg");
		BlogPost forged = blogPostFactory.createBlogPost(g, 1235, null,
				author1, "I am not the owner", singletonList(header),
				emptyList());
		byte[] stream = buildStream(channel.getBlog(),
				forged.getMessage(), image);
		channelManager1.importChannel(new ByteArrayInputStream(stream));
		assertFalse(isStored(forged.getMessage().getId()));
		assertFalse(isStored(image.getId()));
	}

	@Test
	public void testAWrappedPostInAStreamIsSkipped() throws Exception {
		// A wrapped post is checked against a key it carries itself, not
		// the channel's, so a channel never holds one
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager1.subscribeFromLink(channelManager0.getChannelLink(g));
		Blog other = blogManager0.getPersonalBlog(author0);
		BlogPost original = blogPostFactory.createBlogPost(other.getId(),
				1234, null, author0, "Elsewhere");
		BdfList originalBody = c0.getClientHelper()
				.toList(original.getMessage().getBody());
		Message wrapped = blogPostFactory.wrapPost(g,
				other.getGroup().getDescriptor(), 1234, originalBody);
		channelManager1.importChannel(new ByteArrayInputStream(
				buildStream(channel.getBlog(), wrapped)));
		assertFalse(isStored(wrapped.getId()));
	}

	@Test
	public void testAMirrorSayingNothingIsNewIsCheckedWithAnother()
			throws Exception {
		// A mirror that always says nothing has changed must not be able
		// to hold the channel back, whichever mirror is asked first
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		MockWebServer liar = new MockWebServer();
		liar.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				return new MockResponse().setResponseCode(304);
			}
		});
		liar.start();
		AtomicInteger version = new AtomicInteger(1);
		Map<Integer, byte[]> files = new HashMap<>();
		files.put(1, exportChannel(g));
		MockWebServer honest = new MockWebServer();
		honest.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				String tag = "\"v" + version.get() + "\"";
				if (tag.equals(request.getHeader("If-None-Match")))
					return new MockResponse().setResponseCode(304);
				return new MockResponse().setHeader("ETag", tag)
						.setBody(new Buffer().write(
								files.get(version.get())));
			}
		});
		honest.start();
		try {
			channelManager0.setMirrors(g, asList(
					liar.url("/c.briar").toString(),
					honest.url("/c.briar").toString()));
			channelManager1.subscribeFromLink(
					channelManager0.getChannelLink(g));
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(1);
			for (int i = 2; i <= 6; i++) {
				channelManager0.post(g, getRandomString(42));
				files.put(i, exportChannel(g));
				version.set(i);
				assertEquals(FETCHED,
						channelManager1.fetchChannel(g).getOutcome());
				awaitPendingMessageDelivery(1);
			}
			assertEquals(6, blogManager1.getPostHeaders(g).size());
		} finally {
			liar.shutdown();
			honest.shutdown();
		}
	}

	@Test
	public void testANothingNewAnswerWeDidNotAskForIsNotBelieved()
			throws Exception {
		// Without a tag or date to compare, a mirror can't know what we
		// have, so "nothing new" means it is withholding the channel
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		MockWebServer liar = new MockWebServer();
		liar.enqueue(new MockResponse().setResponseCode(304));
		liar.start();
		try {
			subscribeWithMirror(g, liar.url("/c.briar").toString());
			assertEquals(UNREACHABLE,
					channelManager1.fetchChannel(g).getOutcome());
		} finally {
			liar.shutdown();
		}
	}

	@Test
	public void testAMirrorServingJunkCannotMakeTheChannelTooLarge()
			throws Exception {
		// A mirror can serve enough junk to pass the limit on a file.
		// That says nothing about the channel's real file, so another
		// mirror is asked, and the junk doesn't count against the file.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		ClientHelper h = c0.getClientHelper();
		ByteArrayOutputStream junk = new ByteArrayOutputStream();
		junk.write(h.toByteArray(BdfList.of(STREAM_FORMAT_VERSION,
				channel.getBlog().getGroup().getDescriptor())));
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(h.toByteArray(
				BdfList.of(ATTACHMENT.getInt(), "image/jpeg")));
		body.write(getRandomBytes(30_000));
		byte[] entry = h.toByteArray(BdfList.of(1234L, body.toByteArray()));
		while (junk.size() <= MAX_STREAM_BYTES) junk.write(entry);
		MockWebServer liar = new MockWebServer();
		liar.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				return new MockResponse()
						.setBody(new Buffer().write(junk.toByteArray()));
			}
		});
		liar.start();
		MockWebServer honest = serveChannel(g);
		try {
			channelManager0.setMirrors(g, asList(
					liar.url("/c.briar").toString(),
					honest.url("/c.briar").toString()));
			channelManager1.subscribeFromLink(
					channelManager0.getChannelLink(g));
			for (int i = 0; i < 3; i++) {
				FetchResult.Outcome o =
						channelManager1.fetchChannel(g).getOutcome();
				assertTrue(o.toString(), o == FETCHED || o == UNCHANGED);
			}
			awaitPendingMessageDelivery(1);
			assertEquals(1, blogManager1.getPostHeaders(g).size());
		} finally {
			liar.shutdown();
			honest.shutdown();
		}
	}

	@Test
	public void testAPlainHttpMirrorIsRefusedOrSkipped() throws Exception {
		// Over plain HTTP the Tor exit reads and can change the request
		// and the reply, so the owner can't add such a mirror. A link
		// that already names one still subscribes, but it isn't used.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		try {
			channelManager0.setMirrors(g,
					singletonList("http://example.com/c.briar"));
			fail();
		} catch (IllegalArgumentException expected) {
			// Expected
		}
		channelManager0.setMirrors(g, asList("https://example.com/c.briar",
				"http://example7.onion/c.briar"));
		BdfList link = BdfList.of(LINK_FORMAT_VERSION, "Announcements",
				channel.getLocalAuthor().getPublicKey().getEncoded(),
				BdfList.of("http://example.com/c.briar"));
		channelManager1.subscribeFromLink(LINK_PREFIX + Base32.encode(
				c1.getClientHelper().toByteArray(link)).toLowerCase(US));
		assertEquals(NO_SAFE_MIRRORS,
				channelManager1.fetchChannel(g).getOutcome());
	}

	@Test
	public void testATagIsOnlySentBackToTheMirrorThatGaveIt()
			throws Exception {
		// A mirror could give each subscriber a tag of its own. Sent to
		// other mirrors, it would let them recognise the subscriber too.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		channelManager0.post(g, getRandomString(42));
		byte[] file = exportChannel(g);
		List<String> wrongTags = new ArrayList<>();
		MockWebServer[] servers = new MockWebServer[2];
		for (int i = 0; i < 2; i++) {
			String tag = "\"mirror" + i + "\"";
			servers[i] = new MockWebServer();
			servers[i].setDispatcher(new Dispatcher() {
				@Override
				public MockResponse dispatch(RecordedRequest request) {
					String sent = request.getHeader("If-None-Match");
					if (sent != null && !sent.equals(tag)) {
						synchronized (wrongTags) {
							wrongTags.add(sent);
						}
					}
					if (tag.equals(sent))
						return new MockResponse().setResponseCode(304);
					if (request.getHeader("Range") != null) {
						return new MockResponse().setResponseCode(416)
								.setHeader("Content-Range",
										"bytes */" + file.length);
					}
					return new MockResponse().setHeader("ETag", tag)
							.setBody(new Buffer().write(file));
				}
			});
			servers[i].start();
		}
		try {
			channelManager0.setMirrors(g, asList(
					servers[0].url("/c.briar").toString(),
					servers[1].url("/c.briar").toString()));
			channelManager1.subscribeFromLink(
					channelManager0.getChannelLink(g));
			for (int i = 0; i < 6; i++) channelManager1.fetchChannel(g);
			awaitPendingMessageDelivery(1);
			assertTrue(wrongTags.toString(), wrongTags.isEmpty());
		} finally {
			servers[0].shutdown();
			servers[1].shutdown();
		}
	}

	@Test
	public void testATitleCannotHideItsDirection() throws Exception {
		// "report\u202Efdp" shows as "reportpdf" reversed. A title is
		// part of the channel's identity, so a new one is cleaned and
		// one from a link is refused.
		Channel channel = channelManager0.createChannel("news\u202Efdp");
		assertEquals("newsfdp", channel.getTitle());
		Author a = channel.getBlog().getAuthor();
		byte[] raw = c1.getClientHelper().toByteArray(BdfList.of(
				LINK_FORMAT_VERSION, "news\u202Efdp",
				a.getPublicKey().getEncoded(), new BdfList()));
		expectBadLink(LINK_PREFIX + Base32.encode(raw).toLowerCase(US));
	}

	@Test
	public void testATitleCannotFakeALineOrHideCharacters() throws Exception {
		// A line break could print a fake key line above the real one,
		// and invisible characters could make two titles look the same.
		// A new title is cleaned and one from a link is refused. The
		// zero-width non-joiner stays, since Persian needs it.
		Channel fake = channelManager0.createChannel("News\nKey: 1234");
		assertEquals("NewsKey: 1234", fake.getTitle());
		Channel persian =
				channelManager0.createChannel("\u0645\u06cc\u200c\u062e");
		assertEquals("\u0645\u06cc\u200c\u062e", persian.getTitle());
		Author a = fake.getBlog().getAuthor();
		for (String title : asList("News\nKey: 1234", "News\u200b",
				"News\u2028", "News\ufeff")) {
			byte[] raw = c1.getClientHelper().toByteArray(BdfList.of(
					LINK_FORMAT_VERSION, title,
					a.getPublicKey().getEncoded(), new BdfList()));
			expectBadLink(LINK_PREFIX + Base32.encode(raw).toLowerCase(US));
		}
	}

	@Test
	public void testAFileNameCannotHideItsType() {
		FileHeader h = new FileHeader(new GroupId(getRandomId()),
				new MessageId(getRandomId()), "report\u202Efdp.apk",
				"application/pdf", 100);
		assertEquals("reportfdp.apk", h.getName());
	}

	@Test
	public void testSubscribingFromAFileTakesOnlyTheConfirmedChannel()
			throws Exception {
		// The user agrees to the channel the file named when it was
		// first read. If the file has changed since, it isn't taken.
		Channel agreed = channelManager0.createChannel("Announcements");
		Channel other = channelManager0.createChannel("Other");
		channelManager0.post(other.getBlogId(), getRandomString(42));
		try {
			channelManager1.subscribeFromFile(new ByteArrayInputStream(
					exportChannel(other.getBlogId())), agreed.getBlogId());
			fail();
		} catch (FormatException expected) {
			// Expected
		}
		assertTrue(channelManager1.getSubscriptions().isEmpty());
	}

	@Test
	public void testAPostFromTheFutureIsHeldBackNotRejected()
			throws Exception {
		// A reader whose clock is behind sees the owner's posts as from
		// the future. Stored, they would be marked invalid for good, so
		// they are skipped, and the next fetch starts from the first of
		// them, to take them once the clock is right.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		long now = c1.getClock().currentTimeMillis();
		LocalAuthor a = channel.getLocalAuthor();
		Message before = blogPostFactory.createBlogPost(g, now, null, a,
				"Before").getMessage();
		Message future = blogPostFactory.createBlogPost(g,
				now + DAYS.toMillis(2), null, a, "Future").getMessage();
		Message after = blogPostFactory.createBlogPost(g, now + 1, null, a,
				"After").getMessage();
		byte[] file = buildStream(channel.getBlog(), before, future, after);
		long heldBackAt = buildStream(channel.getBlog(), before).length;
		List<String> ranges = new ArrayList<>();
		MockWebServer server = new MockWebServer();
		server.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				String range = request.getHeader("Range");
				if (range == null) {
					return new MockResponse()
							.setBody(new Buffer().write(file));
				}
				synchronized (ranges) {
					ranges.add(range);
				}
				return new MockResponse().setResponseCode(416)
						.setHeader("Content-Range", "bytes */" + file.length);
			}
		});
		server.start();
		try {
			subscribeWithMirror(g, server.url("/c.briar").toString());
			assertEquals(FETCHED, channelManager1.fetchChannel(g).getOutcome());
			awaitPendingMessageDelivery(2);
			assertFalse(isStored(future.getId()));
			channelManager1.fetchChannel(g);
			assertEquals(singletonList("bytes=" + heldBackAt + "-"), ranges);
		} finally {
			server.shutdown();
		}
	}

	@Test
	public void testAFileTooLargeToReadWholeGivesTheNewestPosts()
			throws Exception {
		// A courier's file can outgrow what is read from one file. The
		// file is oldest first, so it is the end that is read.
		Channel channel = channelManager0.createChannel("Announcements");
		GroupId g = channel.getBlogId();
		List<Message> posts = new ArrayList<>();
		long now = c1.getClock().currentTimeMillis();
		for (int i = 0; i < 6; i++) {
			posts.add(blogPostFactory.createBlogPost(g, now + i, null,
					channel.getLocalAuthor(), "Post " + i).getMessage());
		}
		byte[] file = buildStream(channel.getBlog(),
				posts.toArray(new Message[0]));
		// Room for the last two posts, and a little to spare
		long budget = file.length - buildStream(channel.getBlog(),
				posts.subList(0, 4).toArray(new Message[0])).length + 10;
		((ChannelManagerImpl) channelManager1).importFile(
				new ByteArrayInputStream(file), g, true, file.length,
				budget);
		awaitPendingMessageDelivery(2);
		for (int i = 0; i < 4; i++) assertFalse(isStored(posts.get(i).getId()));
		assertTrue(isStored(posts.get(4).getId()));
		assertTrue(isStored(posts.get(5).getId()));
	}

	private boolean isStored(MessageId m) throws Exception {
		try {
			db1.transactionWithResult(true,
					txn -> db1.getMessageState(txn, m));
			return true;
		} catch (NoSuchMessageException e) {
			return false;
		}
	}

	private byte[] buildStream(Blog blog, Message... messages)
			throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(c1.getClientHelper().toByteArray(BdfList.of(
				STREAM_FORMAT_VERSION, blog.getGroup().getDescriptor())));
		for (Message m : messages) {
			out.write(c1.getClientHelper().toByteArray(
					BdfList.of(m.getTimestamp(), m.getBody())));
		}
		return out.toByteArray();
	}

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
