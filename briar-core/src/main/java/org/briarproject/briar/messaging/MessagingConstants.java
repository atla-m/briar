package org.briarproject.briar.messaging;

import static java.util.concurrent.TimeUnit.DAYS;

interface MessagingConstants {

	// Metadata keys for messages
	String MSG_KEY_TIMESTAMP = "timestamp";
	String MSG_KEY_LOCAL = "local";
	String MSG_KEY_MSG_TYPE = "messageType";
	String MSG_KEY_HAS_TEXT = "hasText";
	String MSG_KEY_ATTACHMENT_HEADERS = "attachmentHeaders";
	String MSG_KEY_AUTO_DELETE_TIMER = "autoDeleteTimer";
	/**
	 * The link of the channel a forwarded post came from, if the message
	 * is a forward whose text was checked against the channel's signature.
	 * Forwards from before that check kept their link under "channelLink",
	 * which is no longer read, so their text, which nothing checked, is
	 * shown as the contact's own rather than as the channel's.
	 */
	String MSG_KEY_CHANNEL_LINK = "signedChannelLink";

	/**
	 * How long to keep incoming attachments that aren't listed by any private
	 * message before deleting them.
	 */
	long MISSING_ATTACHMENT_CLEANUP_DURATION_MS = DAYS.toMillis(28);
}
