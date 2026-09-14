package org.briarproject.briar.channel;

import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.test.TestDatabaseConfigModule;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.bramble.api.contact.Contact;
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

import java.util.Collection;
import java.util.List;

import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.NOT_SUPPORTED;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHAREABLE;
import static org.briarproject.bramble.util.StringUtils.getRandomString;
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
