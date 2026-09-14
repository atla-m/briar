package org.briarproject.briar.api.blog;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface BlogFactory {

	/**
	 * Creates a personal blog for a given author.
	 */
	Blog createBlog(Author author);

	/**
	 * Creates a RSS feed blog for a given author.
	 */
	Blog createFeedBlog(Author author);

	/**
	 * Creates a channel for a given author, which is a key pair created
	 * for the channel rather than a user's identity.
	 */
	Blog createChannelBlog(Author author);

	/**
	 * Parses a blog with the given Group
	 */
	Blog parseBlog(Group g) throws FormatException;

	/**
	 * Parses a blog from its group descriptor, which may have been
	 * received from a contact.
	 */
	Blog parseBlog(BdfList descriptor) throws FormatException;

}
