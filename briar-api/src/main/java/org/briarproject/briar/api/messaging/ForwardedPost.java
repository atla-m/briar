package org.briarproject.briar.api.messaging;

import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * A channel post as a private message carries it: the channel's link and
 * the parts of the post that its signature covers. The recipient checks
 * the signature with the key in the link, so the text shown as the
 * channel's really was posted to it, and can store the post in the
 * channel on subscribing, so the channel isn't empty until a fetch.
 */
@Immutable
@NotNullByDefault
public class ForwardedPost {

	private final String channelLink;
	private final long timestamp;
	private final String text;
	@Nullable
	private final BdfList attachmentHeaders;
	private final byte[] signature;

	public ForwardedPost(String channelLink, long timestamp, String text,
			@Nullable BdfList attachmentHeaders, byte[] signature) {
		this.channelLink = channelLink;
		this.timestamp = timestamp;
		this.text = text;
		this.attachmentHeaders = attachmentHeaders;
		this.signature = signature;
	}

	/**
	 * Returns the link of the channel the post came from.
	 */
	public String getChannelLink() {
		return channelLink;
	}

	/**
	 * Returns the timestamp of the post in the channel, which its
	 * signature covers.
	 */
	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * Returns the text of the post, which its signature covers.
	 */
	public String getText() {
		return text;
	}

	/**
	 * Returns the headers of the images and files the post carried, as
	 * its signature covers them, or null if it carried none. The images
	 * and files themselves aren't forwarded.
	 */
	@Nullable
	public BdfList getAttachmentHeaders() {
		return attachmentHeaders;
	}

	/**
	 * Returns the channel's signature over the post.
	 */
	public byte[] getSignature() {
		return signature;
	}
}
