package org.briarproject.briar.api.privategroup;

import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface PrivateGroupConstants {

	/**
	 * The maximum length of a group's name in UTF-8 bytes.
	 */
	int MAX_GROUP_NAME_LENGTH = 100;

	/**
	 * The length of a group's random salt in bytes.
	 */
	int GROUP_SALT_LENGTH = 32;

	/**
	 * The maximum length of a group post's text in UTF-8 bytes.
	 */
	int MAX_GROUP_POST_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 1024;

	/**
	 * The maximum length of a group invitation's optional text in UTF-8 bytes.
	 */
	int MAX_GROUP_INVITATION_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 1024;

	/**
	 * The maximum number of attachments per group post.
	 */
	int MAX_GROUP_POST_ATTACHMENTS = 10;

	/**
	 * The maximum length of a shared file's name in UTF-8 bytes.
	 */
	int MAX_FILE_NAME_LENGTH = 255;

	/**
	 * The number of file bytes carried by each {@link MessageType#FILE_CHUNK}
	 * message. This leaves room for the chunk's BDF descriptor within the
	 * maximum message body length. Every chunk of a file except the last
	 * carries exactly this many bytes.
	 */
	int FILE_CHUNK_PAYLOAD_LENGTH = MAX_MESSAGE_BODY_LENGTH - 16;

	/**
	 * The maximum size of a file shared in a group. Files are stored by every
	 * member and are often transferred over Bluetooth or other slow
	 * transports, so this is deliberately modest.
	 */
	long MAX_GROUP_FILE_SIZE = 10L * 1024 * 1024;

	/**
	 * The maximum number of chunks in a file, which follows from the maximum
	 * file size and the chunk payload length.
	 */
	int MAX_FILE_CHUNKS = (int) ((MAX_GROUP_FILE_SIZE
			+ FILE_CHUNK_PAYLOAD_LENGTH - 1) / FILE_CHUNK_PAYLOAD_LENGTH);

}
