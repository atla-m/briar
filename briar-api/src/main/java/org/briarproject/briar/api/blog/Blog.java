package org.briarproject.briar.api.blog;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.briar.api.client.BaseGroup;
import org.briarproject.briar.api.sharing.Shareable;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Blog extends BaseGroup implements Shareable {

	private final Author author;
	private final boolean rssFeed;
	private final boolean channel;

	public Blog(Group group, Author author, boolean rssFeed) {
		this(group, author, rssFeed, false);
	}

	public Blog(Group group, Author author, boolean rssFeed,
			boolean channel) {
		super(group);
		this.author = author;
		this.rssFeed = rssFeed;
		this.channel = channel;
	}

	public Author getAuthor() {
		return author;
	}

	public boolean isRssFeed() {
		return rssFeed;
	}

	/**
	 * Returns true if this blog is a channel: a blog published under a key
	 * pair of its own rather than under the identity of the user who
	 * created it. Only the holder of that key pair can post to it.
	 */
	public boolean isChannel() {
		return channel;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Blog && super.equals(o);
	}

	/**
	 * Returns the blog's author's name, not the name as shown in the UI.
	 */
	@Override
	public String getName() {
		return author.getName();
	}

}
