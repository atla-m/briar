package org.briarproject.briar.android.blog;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * A row on the channels screen: either a channel we created, which we can
 * post to, or one we subscribe to, which we can only read. Both can be
 * fetched from their mirrors, which is why subscriptions are listed here
 * rather than only among the blogs.
 */
@Immutable
@NotNullByDefault
class ChannelItem {

	private final GroupId id;
	private final String title;
	private final String fingerprint;
	private final long created;
	private final boolean owned;

	static ChannelItem owned(Channel channel) {
		return new ChannelItem(channel.getBlogId(), channel.getTitle(),
				ChannelFingerprint.of(channel.getBlog().getAuthor()),
				channel.getCreated(), true);
	}

	static ChannelItem subscribed(Blog blog) {
		return new ChannelItem(blog.getId(), blog.getName(),
				ChannelFingerprint.of(blog.getAuthor()), 0, false);
	}

	private ChannelItem(GroupId id, String title, String fingerprint,
			long created, boolean owned) {
		this.id = id;
		this.title = title;
		this.fingerprint = fingerprint;
		this.created = created;
		this.owned = owned;
	}

	GroupId getId() {
		return id;
	}

	String getTitle() {
		return title;
	}

	String getFingerprint() {
		return fingerprint;
	}

	long getCreated() {
		return created;
	}

	boolean isOwned() {
		return owned;
	}
}
