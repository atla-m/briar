package org.briarproject.briar.android.blog;

import org.briarproject.briar.api.blog.Blog;

class BlogItem {

	private final Blog blog;
	private final boolean ours, removable, ownChannel;

	BlogItem(Blog blog, boolean ours, boolean removable,
			boolean ownChannel) {
		this.blog = blog;
		this.ours = ours;
		this.removable = removable;
		this.ownChannel = ownChannel;
	}

	Blog getBlog() {
		return blog;
	}

	boolean isOurs() {
		return ours;
	}

	boolean canBeRemoved() {
		return removable;
	}

	/**
	 * Returns true if this is a channel we created. A channel is signed
	 * by a key of its own, so {@link #isOurs()} is false for it.
	 */
	boolean isOwnChannel() {
		return ownChannel;
	}
}
