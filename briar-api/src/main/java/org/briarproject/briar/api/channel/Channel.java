package org.briarproject.briar.api.channel;

import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * A channel we created and hold the key pair for, so we can post to it.
 * Subscribers hold the channel's blog without this key pair, and can only
 * read.
 */
@Immutable
@NotNullByDefault
public class Channel {

	private final Blog blog;
	private final LocalAuthor localAuthor;
	private final long created;

	public Channel(Blog blog, LocalAuthor localAuthor, long created) {
		this.blog = blog;
		this.localAuthor = localAuthor;
		this.created = created;
	}

	public Blog getBlog() {
		return blog;
	}

	public GroupId getBlogId() {
		return blog.getId();
	}

	/**
	 * Returns the channel's key pair. Anyone holding it can post to the
	 * channel, so it must not leave the device except deliberately.
	 */
	public LocalAuthor getLocalAuthor() {
		return localAuthor;
	}

	public String getTitle() {
		return blog.getName();
	}

	public long getCreated() {
		return created;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Channel &&
				blog.equals(((Channel) o).blog);
	}

	@Override
	public int hashCode() {
		return blog.hashCode();
	}
}
