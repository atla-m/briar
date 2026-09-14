package org.briarproject.briar.api.channel;

import java.util.regex.Pattern;

import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface ChannelConstants {

	/**
	 * The prefix of a channel link, which carries everything needed to
	 * subscribe to a channel: its title and public key.
	 */
	String LINK_PREFIX = "briar-channel://";

	/**
	 * The current version of the channel link format.
	 */
	int LINK_FORMAT_VERSION = 0;

	/**
	 * Matches a channel link, with or without its prefix.
	 */
	Pattern LINK_REGEX = Pattern.compile(
			"(briar-channel://)?([a-z2-7]{16,512})");

	/**
	 * The largest channel link we will parse, before base32 decoding.
	 */
	int MAX_LINK_BYTES = 512;

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
