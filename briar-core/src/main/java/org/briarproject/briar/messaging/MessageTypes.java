package org.briarproject.briar.messaging;

interface MessageTypes {

	int PRIVATE_MESSAGE = 0;
	int ATTACHMENT = 1;
	/**
	 * Describes a file shared in the conversation: its name, type, size and
	 * the IDs of the {@link #FILE_CHUNK} messages that hold its bytes, in
	 * order. A {@link #PRIVATE_MESSAGE} references the manifest by message
	 * ID. Manifests aren't shown as messages themselves.
	 */
	int FILE_MANIFEST = 2;
	/**
	 * One piece of a file, small enough to fit in a single sync message so
	 * that files can be transferred piece by piece over slow or intermittent
	 * transports such as Bluetooth, resuming where a lost connection left off.
	 */
	int FILE_CHUNK = 3;
	/**
	 * Asks for the chunks of a file its sender held back because it is
	 * larger than
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#MAX_PUSHED_FILE_SIZE}.
	 * Unsigned; names only the manifest. Not shown as a message.
	 */
	int FILE_REQUEST = 4;
}
