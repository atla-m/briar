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
}
