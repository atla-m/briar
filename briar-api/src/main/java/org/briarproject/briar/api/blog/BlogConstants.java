package org.briarproject.briar.api.blog;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface BlogConstants {

	/**
	 * The maximum length of a blog post's text in UTF-8 bytes.
	 */
	int MAX_BLOG_POST_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 1024;

	/**
	 * The maximum length of a blog comment's text in UTF-8 bytes.
	 */
	int MAX_BLOG_COMMENT_TEXT_LENGTH = MAX_BLOG_POST_TEXT_LENGTH;

	/**
	 * The maximum number of images and files a blog post can carry.
	 */
	int MAX_BLOG_POST_ATTACHMENTS = 10;

	/**
	 * How long to keep an image or file that no post references, before
	 * deleting it.
	 */
	long MISSING_ATTACHMENT_CLEANUP_DURATION_MS = DAYS.toMillis(28);

	// Metadata keys
	/**
	 * Group metadata key for the timestamp of the newest message we have
	 * added to a blog ourselves. A new message is timestamped after it,
	 * so the messages we write are strictly ordered even when several are
	 * written within the same millisecond.
	 */
	String GROUP_KEY_LATEST_TIMESTAMP = "latestTimestamp";

	String KEY_TYPE = "type";
	String KEY_TIMESTAMP = "timestamp";
	String KEY_TIME_RECEIVED = "timeReceived";
	String KEY_AUTHOR = "author";
	String KEY_RSS_FEED = "rssFeed";
	String KEY_ATTACHMENT_HEADERS = "attachmentHeaders";
	String KEY_READ = "read";
	String KEY_COMMENT = "comment";
	String KEY_ORIGINAL_MSG_ID = "originalMessageId";
	String KEY_ORIGINAL_PARENT_MSG_ID = "originalParentMessageId";
	/**
	 * This is the ID of either a message wrapped from a different group
	 * or of a message from the same group that therefore needed no wrapping.
	 */
	String KEY_PARENT_MSG_ID = "parentMessageId";

}
