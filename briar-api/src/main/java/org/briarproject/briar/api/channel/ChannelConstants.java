package org.briarproject.briar.api.channel;

import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface ChannelConstants {

	/**
	 * The current version of the channel stream format: the file a channel's
	 * owner publishes and its subscribers fetch.
	 */
	int STREAM_FORMAT_VERSION = 0;

	/**
	 * The largest channel stream we will read. A stream is published by an
	 * untrusted host, so it must not be able to fill our storage.
	 */
	long MAX_STREAM_BYTES = 10L * 1024 * 1024;

	/**
	 * The largest number of messages we will read from a channel stream.
	 */
	int MAX_STREAM_MESSAGES =
			(int) (MAX_STREAM_BYTES / MAX_MESSAGE_BODY_LENGTH) + 1;
}
