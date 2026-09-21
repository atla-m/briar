package org.briarproject.briar.api.messaging;

import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface MessagingConstants {

	/**
	 * The maximum length of a private message's text in UTF-8 bytes.
	 */
	int MAX_PRIVATE_MESSAGE_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 2048;

	/**
	 * The maximum number of attachments per private message.
	 */
	int MAX_ATTACHMENTS_PER_MESSAGE = 10;

	/**
	 * The maximum length of the channel link carried by a forwarded post,
	 * in UTF-8 bytes. A link is base32 with a short prefix, so this is the
	 * longest link the channel client will parse plus room for the prefix.
	 */
	int MAX_FORWARDED_LINK_LENGTH = 1024;

}
