package org.briarproject.briar.api.messaging;

public enum PrivateMessageFormat {

	/**
	 * First version of the private message format, which doesn't support
	 * image attachments or auto-deletion.
	 */
	TEXT_ONLY,

	/**
	 * Second version of the private message format, which supports image
	 * attachments but not auto-deletion. Support for this format was
	 * added in client version 0.1.
	 */
	TEXT_IMAGES,

	/**
	 * Third version of the private message format, which supports image
	 * attachments and auto-deletion. Support for this format was added
	 * in client version 0.3.
	 */
	TEXT_IMAGES_AUTO_DELETE,

	/**
	 * Fourth version of the private message format, which additionally
	 * supports files of any type up to
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#MAX_FILE_SIZE},
	 * transferred in chunks, and images larger than a single message.
	 * Support for this format was added in client version 0.4.
	 */
	TEXT_IMAGES_AUTO_DELETE_FILES,

	/**
	 * Fifth version of the private message format, which additionally
	 * supports forwarding a channel post: the message carries the link of
	 * the channel the text came from, so the recipient can subscribe to it
	 * rather than take the sender's word for where it came from. Support
	 * for this format was added in client version 0.5.
	 */
	TEXT_IMAGES_AUTO_DELETE_FILES_FORWARD;

	/**
	 * Returns true if this format supports image attachments.
	 */
	public boolean supportsImages() {
		return this != TEXT_ONLY;
	}

	/**
	 * Returns true if this format supports auto-deletion.
	 */
	public boolean supportsAutoDelete() {
		return this == TEXT_IMAGES_AUTO_DELETE ||
				this == TEXT_IMAGES_AUTO_DELETE_FILES;
	}

	/**
	 * Returns true if this format supports chunked files.
	 */
	public boolean supportsFiles() {
		return this == TEXT_IMAGES_AUTO_DELETE_FILES ||
				this == TEXT_IMAGES_AUTO_DELETE_FILES_FORWARD;
	}

	/**
	 * Returns true if this format can carry a forwarded channel post.
	 */
	public boolean supportsForwarding() {
		return this == TEXT_IMAGES_AUTO_DELETE_FILES_FORWARD;
	}
}
