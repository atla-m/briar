package org.briarproject.briar.api.channel;

import java.util.regex.Pattern;

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
	 * The largest number of mirrors a channel link may name. A mirror only
	 * stores a file it can't alter, so several are cheap, but a link has to
	 * stay short enough to pass around.
	 */
	int MAX_MIRRORS = 5;

	/**
	 * The largest length of a mirror's URL.
	 */
	int MAX_MIRROR_LENGTH = 256;

	/**
	 * How long to wait before fetching channels for the first time after
	 * Tor becomes active, in minutes.
	 */
	int FETCH_DELAY_INITIAL = 1;

	/**
	 * How often to fetch channels from their mirrors, in minutes.
	 */
	int FETCH_INTERVAL = 30;

	/**
	 * The current version of the channel stream format: the file a channel's
	 * owner publishes and its subscribers fetch.
	 */
	int STREAM_FORMAT_VERSION = 0;

	/**
	 * The largest channel stream we will read from mirrors, counted over
	 * the whole file across every fetch of it. A stream is served by an
	 * untrusted host, and a message it serves that never becomes
	 * deliverable, such as a chunk naming a manifest that never comes, is
	 * kept for ever, so a limit counted per fetch would let a hostile
	 * mirror add more every half hour for as long as we fetch.
	 */
	long MAX_STREAM_BYTES = 10L * 1024 * 1024;

	/**
	 * The smallest a message in an honest channel stream can usefully be,
	 * in bytes: a post needs its signature, its text and the stream's own
	 * framing, which together come to about this much for a post of a
	 * few words. Used to derive message limits that bind only on streams
	 * padded with messages too small to be anything but junk.
	 */
	int MIN_HONEST_MESSAGE_BYTES = 128;

	/**
	 * The largest number of messages we will read from mirrors for one
	 * channel, counted over the whole file like {@link #MAX_STREAM_BYTES}.
	 * It bounds how many database rows an untrusted host can make us keep,
	 * however small the messages. It is derived from the smallest honest
	 * message rather than the largest message, so that an honest channel
	 * reaches the byte limit first; deriving it from the largest message
	 * allowed only a few hundred, and a channel of short posts stopped
	 * updating for good after that many.
	 */
	int MAX_STREAM_MESSAGES =
			(int) (MAX_STREAM_BYTES / MIN_HONEST_MESSAGE_BYTES);

	/**
	 * The largest channel file we will read when the user opens one by
	 * hand. Each file is counted on its own, because the user chose it,
	 * and reading the same file again stores nothing new; so this can be
	 * far larger than {@link #MAX_STREAM_BYTES}, large enough for a
	 * channel that carries its images and files, while still bounding the
	 * work a file can cause.
	 */
	long MAX_IMPORT_BYTES = 100L * 1024 * 1024;

	/**
	 * The largest number of messages we will read from a channel file
	 * opened by hand, derived like {@link #MAX_STREAM_MESSAGES}.
	 */
	int MAX_IMPORT_MESSAGES =
			(int) (MAX_IMPORT_BYTES / MIN_HONEST_MESSAGE_BYTES);
}
