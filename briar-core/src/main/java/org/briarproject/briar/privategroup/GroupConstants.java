package org.briarproject.briar.privategroup;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;

interface GroupConstants {

	// Metadata keys
	String KEY_TYPE = "type";
	String KEY_TIMESTAMP = "timestamp";
	String KEY_READ = MSG_KEY_READ;
	String KEY_PARENT_MSG_ID = "parentMsgId";
	String KEY_PREVIOUS_MSG_ID = "previousMsgId";
	String KEY_MEMBER = "member";
	String KEY_INITIAL_JOIN_MSG = "initialJoinMsg";
	String KEY_HAS_TEXT = "hasText";
	/**
	 * The list of attachment and file entries carried by a post, as encoded
	 * in the post's body. Entries with two elements are image attachments,
	 * entries with four elements are shared files.
	 */
	String KEY_ATTACHMENT_HEADERS = "attachmentHeaders";

	String GROUP_KEY_MEMBERS = "members";
	String GROUP_KEY_OUR_GROUP = "ourGroup";
	String GROUP_KEY_CREATOR_ID = "creatorId";
	String GROUP_KEY_DISSOLVED = "dissolved";
	String GROUP_KEY_VISIBILITY = "visibility";

	/**
	 * How long to keep incoming attachments, file manifests and file chunks
	 * that aren't referenced by any post (or, for chunks, by any manifest)
	 * before deleting them.
	 */
	long MISSING_ATTACHMENT_CLEANUP_DURATION_MS = DAYS.toMillis(28);

}
