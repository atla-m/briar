package org.briarproject.briar.api.attachment;

import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface MediaConstants {

	// Metadata keys for messages
	String MSG_KEY_CONTENT_TYPE = "contentType";
	String MSG_KEY_DESCRIPTOR_LENGTH = "descriptorLength";

	/**
	 * The maximum length of an attachment's content type in UTF-8 bytes.
	 */
	int MAX_CONTENT_TYPE_BYTES = 80;

	/**
	 * The maximum allowed size of image attachments.
	 * TODO: Different limit for GIFs?
	 */
	int MAX_IMAGE_SIZE = MAX_MESSAGE_BODY_LENGTH - 100; // 6 * 1024 * 1024;

	/**
	 * The maximum length of a shared file's name in UTF-8 bytes.
	 */
	int MAX_FILE_NAME_LENGTH = 255;

	/**
	 * The number of file bytes carried by each file chunk message. This
	 * leaves room for the chunk's BDF descriptor within the maximum message
	 * body length. Every chunk of a file except the last carries exactly
	 * this many bytes.
	 */
	int FILE_CHUNK_PAYLOAD_LENGTH = MAX_MESSAGE_BODY_LENGTH - 16;

	/**
	 * The maximum size of a file shared in a private group or a private
	 * conversation. Files are stored by every member of a group and are
	 * often transferred over Bluetooth or other slow transports, so this is
	 * deliberately modest.
	 */
	long MAX_FILE_SIZE = 10L * 1024 * 1024;

	/**
	 * The maximum number of chunks in a file, which follows from the maximum
	 * file size and the chunk payload length.
	 */
	int MAX_FILE_CHUNKS = (int) ((MAX_FILE_SIZE + FILE_CHUNK_PAYLOAD_LENGTH
			- 1) / FILE_CHUNK_PAYLOAD_LENGTH);
}
