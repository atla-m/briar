package org.briarproject.briar.blog;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupFactory;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.api.blog.Blog;
import org.jmock.Expectations;
import org.junit.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.briarproject.bramble.test.TestUtils.getAuthor;
import static org.briarproject.bramble.util.StringUtils.toUtf8;
import static org.briarproject.briar.api.blog.BlogManager.CLIENT_ID;
import static org.briarproject.briar.api.blog.BlogManager.MAJOR_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the blog group descriptor, which decides a blog's identity. A
 * channel must not collide with a personal blog or an RSS feed by the same
 * author, and a descriptor received from a contact must be rejected unless
 * it is one of the three shapes we understand.
 */
public class BlogFactoryImplTest extends BrambleMockTestCase {

	private final GroupFactory groupFactory = context.mock(GroupFactory.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);

	private final Author author = getAuthor();
	private final BdfList authorList = BdfList.of(author.getFormatVersion(),
			author.getName(), author.getPublicKey());

	private final BlogFactoryImpl factory =
			new BlogFactoryImpl(groupFactory, clientHelper);

	/**
	 * Encodes descriptors distinctly, so that two blogs get the same group
	 * only if they have the same descriptor, and returns each group's
	 * descriptor when asked to decode it.
	 */
	private void expectEncoding() throws Exception {
		List<byte[]> encoded = new ArrayList<>();
		List<BdfList> decoded = new ArrayList<>();
		context.checking(new Expectations() {{
			allowing(clientHelper).toList(author);
			will(returnValue(authorList));
			allowing(clientHelper).parseAndValidateAuthor(authorList);
			will(returnValue(author));
			allowing(clientHelper).toByteArray(with(any(BdfList.class)));
			will(new org.jmock.api.Action() {
				@Override
				public Object invoke(org.jmock.api.Invocation i) {
					BdfList l = (BdfList) i.getParameter(0);
					byte[] bytes = toUtf8(l.toString());
					encoded.add(bytes);
					decoded.add(l);
					return bytes;
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("encodes a BDF list");
				}
			});
			allowing(clientHelper).toList(with(any(byte[].class)));
			will(new org.jmock.api.Action() {
				@Override
				public Object invoke(org.jmock.api.Invocation i) {
					byte[] bytes = (byte[]) i.getParameter(0);
					for (int j = 0; j < encoded.size(); j++) {
						if (java.util.Arrays.equals(encoded.get(j), bytes)) {
							return decoded.get(j);
						}
					}
					throw new AssertionError("Unknown descriptor");
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("decodes a BDF list");
				}
			});
			allowing(groupFactory).createGroup(with(CLIENT_ID),
					with(MAJOR_VERSION), with(any(byte[].class)));
			will(new org.jmock.api.Action() {
				@Override
				public Object invoke(org.jmock.api.Invocation i) {
					byte[] descriptor = (byte[]) i.getParameter(2);
					// Stands in for the real hash: equal descriptors give
					// equal group IDs, different ones don't
					return new Group(new GroupId(digest(descriptor)),
							CLIENT_ID, MAJOR_VERSION, descriptor);
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("creates a group");
				}
			});
		}});
	}

	@Test
	public void testChannelDoesNotCollideWithBlogOrFeed() throws Exception {
		expectEncoding();
		Blog personal = factory.createBlog(author);
		Blog rss = factory.createFeedBlog(author);
		Blog channel = factory.createChannelBlog(author);
		// The same author gives three distinct blogs
		assertNotEquals(personal.getId(), rss.getId());
		assertNotEquals(personal.getId(), channel.getId());
		assertNotEquals(rss.getId(), channel.getId());
		assertFalse(personal.isChannel());
		assertFalse(rss.isChannel());
		assertTrue(channel.isChannel());
		assertFalse(channel.isRssFeed());
	}

	@Test
	public void testSameKeyPairGivesSameChannel() throws Exception {
		expectEncoding();
		// A channel's ID follows from its key pair and title, so everyone
		// given them derives the same channel
		assertEquals(factory.createChannelBlog(author).getId(),
				factory.createChannelBlog(author).getId());
	}

	@Test
	public void testParsesTheThreeDescriptorShapes() throws Exception {
		expectEncoding();
		Blog personal = factory.parseBlog(BdfList.of(authorList, false));
		assertFalse(personal.isRssFeed());
		assertFalse(personal.isChannel());

		Blog rss = factory.parseBlog(BdfList.of(authorList, true));
		assertTrue(rss.isRssFeed());
		assertFalse(rss.isChannel());

		Blog channel = factory.parseBlog(BdfList.of(authorList, false, true));
		assertFalse(channel.isRssFeed());
		assertTrue(channel.isChannel());
	}

	@Test
	public void testParsingAGroupAgreesWithCreatingIt() throws Exception {
		expectEncoding();
		Group g = factory.createChannelBlog(author).getGroup();
		Blog parsed = factory.parseBlog(g);
		assertEquals(g.getId(), parsed.getId());
		assertTrue(parsed.isChannel());
	}

	@Test
	public void testRejectsMalformedDescriptors() throws Exception {
		expectEncoding();
		// A channel is not an RSS feed
		expectRejected(BdfList.of(authorList, true, true));
		// The third element is only there to mark a channel, so it can't
		// be false: that would be a second spelling of a personal blog,
		// with a different group ID
		expectRejected(BdfList.of(authorList, false, false));
		// Wrong number of elements
		expectRejected(BdfList.of(authorList));
		expectRejected(BdfList.of(authorList, false, true, true));
	}

	private static byte[] digest(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	private void expectRejected(BdfList descriptor) {
		try {
			factory.parseBlog(descriptor);
			fail("Accepted " + descriptor);
		} catch (FormatException expected) {
			// Expected
		}
	}
}
