package org.briarproject.briar.blog;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupFactory;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.briar.api.blog.BlogManager.CLIENT_ID;
import static org.briarproject.briar.api.blog.BlogManager.MAJOR_VERSION;

@Immutable
@NotNullByDefault
class BlogFactoryImpl implements BlogFactory {

	private final GroupFactory groupFactory;
	private final ClientHelper clientHelper;

	@Inject
	BlogFactoryImpl(GroupFactory groupFactory, ClientHelper clientHelper) {

		this.groupFactory = groupFactory;
		this.clientHelper = clientHelper;
	}

	@Override
	public Blog createBlog(Author a) {
		return createBlog(a, false, false);
	}

	@Override
	public Blog createFeedBlog(Author a) {
		return createBlog(a, true, false);
	}

	@Override
	public Blog createChannelBlog(Author a) {
		return createBlog(a, false, true);
	}

	private Blog createBlog(Author a, boolean rssFeed, boolean channel) {
		try {
			// Personal blogs and RSS feeds keep the original two-element
			// descriptor, so their group IDs don't change. A channel adds
			// a third element, which gives it a different group ID and
			// makes it unreadable to clients that don't know about
			// channels.
			BdfList blog = channel
					? BdfList.of(clientHelper.toList(a), rssFeed, true)
					: BdfList.of(clientHelper.toList(a), rssFeed);
			byte[] descriptor = clientHelper.toByteArray(blog);
			Group g = groupFactory.createGroup(CLIENT_ID, MAJOR_VERSION,
					descriptor);
			return new Blog(g, a, rssFeed, channel);
		} catch (FormatException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Blog parseBlog(Group g) throws FormatException {
		BdfList descriptor = clientHelper.toList(g.getDescriptor());
		return parseBlog(descriptor);
	}

	@Override
	public Blog parseBlog(BdfList descriptor) throws FormatException {
		// Author, RSS feed, and for a channel a third element
		if (descriptor.size() != 2 && descriptor.size() != 3)
			throw new FormatException();
		BdfList authorList = descriptor.getList(0);
		boolean rssFeed = descriptor.getBoolean(1);
		Author author = clientHelper.parseAndValidateAuthor(authorList);
		if (descriptor.size() == 2) {
			return rssFeed ? createFeedBlog(author) : createBlog(author);
		}
		// The third element is only present to mark a channel, so it
		// can't be false, and a channel is not an RSS feed
		if (rssFeed || !descriptor.getBoolean(2))
			throw new FormatException();
		return createChannelBlog(author);
	}
}
