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
	int FILE_CHUNK_PAYLOAD_LENGTH = MAX_MESSAGE_BODY_LENGTH - 48;

	/**
	 * The maximum size of a file shared in a private group, a private
	 * conversation or a blog. This is the largest file one manifest can
	 * describe: a manifest lists every chunk's hash in a single message, so
	 * it can list about 950 chunks, and this leaves a margin below that.
	 * Every device applies the same limit, so a file that is valid for one
	 * member is valid for all and anyone who holds it can pass it on.
	 */
	long MAX_FILE_SIZE = 29L * 1024 * 1024;

	/**
	 * Files up to this size are sent to everyone who can see them, as soon
	 * as the message that shares them is sent. Larger files are held back:
	 * only their manifest is sent, so recipients see the name and size, and
	 * the chunks follow when someone asks for them. This is the size files
	 * were limited to before, so every file that could be sent before
	 * still spreads exactly as it did.
	 */
	long MAX_PUSHED_FILE_SIZE = 10L * 1024 * 1024;

	/**
	 * The largest a chunked image may be after compression. Images are
	 * decoded and compressed whole in memory, so this stays independent of
	 * {@link #MAX_FILE_SIZE}.
	 */
	long MAX_CHUNKED_IMAGE_SIZE = 10L * 1024 * 1024;

	/**
	 * The free space, in bytes, that storing a file of our own must leave
	 * behind. The database needs room to compact itself and to keep
	 * accepting messages; a phone that runs out of it can lose the file
	 * being stored and, before this was guarded, could leave Briar unable
	 * to open. Measured on a device, compacting a 134 MB database needed
	 * about 25 MB.
	 */
	long MIN_FREE_SPACE_AFTER_FILE = 64L * 1024 * 1024;

	/**
	 * The maximum number of chunks in a file, which follows from the maximum
	 * file size and the chunk payload length.
	 */
	int MAX_FILE_CHUNKS = (int) ((MAX_FILE_SIZE + FILE_CHUNK_PAYLOAD_LENGTH
			- 1) / FILE_CHUNK_PAYLOAD_LENGTH);
}
