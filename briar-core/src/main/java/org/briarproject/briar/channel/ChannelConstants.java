package org.briarproject.briar.channel;

interface ChannelConstants {

	/**
	 * Group metadata key for the list of channels we own.
	 */
	String KEY_CHANNELS = "channels";

	// Keys within a channel's entry in that list
	String KEY_CHANNEL_AUTHOR = "channelAuthor";
	String KEY_CHANNEL_PRIVATE_KEY = "channelPrivateKey";
	String KEY_CHANNEL_CREATED = "channelCreated";

	// Group metadata on a channel's own group, kept by owner and
	// subscriber alike
	String GROUP_KEY_MIRRORS = "channelMirrors";
	String GROUP_KEY_ETAG = "channelEtag";
	String GROUP_KEY_LAST_MODIFIED = "channelLastModified";

	/**
	 * Group metadata key for how many bytes of the channel's published
	 * file we have read and imported, so the next fetch can ask for the
	 * rest instead of the whole file again.
	 */
	String GROUP_KEY_FETCH_OFFSET = "channelFetchOffset";

	/**
	 * Group metadata key for how many of the channel's messages we have
	 * read and imported from that file, so that the limit on how many a
	 * file may carry applies to the file rather than to each fetch.
	 */
	String GROUP_KEY_FETCH_MESSAGES = "channelFetchMessages";

}
