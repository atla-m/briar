package org.briarproject.briar.messaging;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.BdfMessageContext;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfReaderFactory;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.channel.ChannelLinkParser;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.MessageContext;
import org.briarproject.bramble.api.sync.validation.MessageValidator;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.attachment.ChunkedFileStore;
import org.briarproject.briar.attachment.CountingInputStream;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.io.InputStream;

import javax.annotation.concurrent.Immutable;

import static java.util.Collections.singletonList;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_NAME_LENGTH;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.briarproject.briar.attachment.ChunkedFileStore.KEY_FILE_MANIFEST_ID;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_ATTACHMENTS_PER_MESSAGE;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.briarproject.briar.api.blog.BlogPostFactory.SIGNING_LABEL_POST;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_FORWARDED_LINK_LENGTH;
import static org.briarproject.briar.blog.BlogPostValidator.getSignedPost;
import static org.briarproject.briar.blog.BlogPostValidator.validateAttachmentHeaders;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;
import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.briarproject.briar.messaging.MessageTypes.ATTACHMENT;
import static org.briarproject.briar.messaging.MessageTypes.FILE_CHUNK;
import static org.briarproject.briar.messaging.MessageTypes.FILE_MANIFEST;
import static org.briarproject.briar.messaging.MessageTypes.FILE_REQUEST;
import static org.briarproject.briar.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_CHANNEL_LINK;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_AUTO_DELETE_TIMER;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_HAS_TEXT;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;
import static org.briarproject.briar.util.ValidationUtils.validateAutoDeleteTimer;

@Immutable
@NotNullByDefault
class PrivateMessageValidator implements MessageValidator {

	private final BdfReaderFactory bdfReaderFactory;
	private final MetadataEncoder metadataEncoder;
	private final Clock clock;
	private final ClientHelper clientHelper;
	private final ChannelLinkParser linkParser;

	PrivateMessageValidator(BdfReaderFactory bdfReaderFactory,
			MetadataEncoder metadataEncoder, Clock clock,
			ClientHelper clientHelper, ChannelLinkParser linkParser) {
		this.bdfReaderFactory = bdfReaderFactory;
		this.metadataEncoder = metadataEncoder;
		this.clock = clock;
		this.clientHelper = clientHelper;
		this.linkParser = linkParser;
	}

	@Override
	public MessageContext validateMessage(Message m, Group g)
			throws InvalidMessageException {
		// Reject the message if it's too far in the future
		long now = clock.currentTimeMillis();
		if (m.getTimestamp() - now > MAX_CLOCK_DIFFERENCE) {
			throw new InvalidMessageException(
					"Timestamp is too far in the future");
		}
		try {
			// TODO: Support large messages
			InputStream in = new ByteArrayInputStream(m.getBody());
			CountingInputStream countIn =
					new CountingInputStream(in, MAX_MESSAGE_BODY_LENGTH);
			BdfReader reader = bdfReaderFactory.createReader(countIn);
			BdfList list = reader.readList();
			long bytesRead = countIn.getBytesRead();
			BdfMessageContext context;
			if (list.size() == 1) {
				// Legacy private message
				if (!reader.eof()) throw new FormatException();
				context = validateLegacyPrivateMessage(m, list);
			} else {
				// Private message or attachment
				int messageType = list.getInt(0);
				if (messageType == PRIVATE_MESSAGE) {
					if (!reader.eof()) throw new FormatException();
					context = validatePrivateMessage(m, list);
				} else if (messageType == ATTACHMENT) {
					context = validateAttachment(m, list, bytesRead);
				} else if (messageType == FILE_MANIFEST) {
					if (!reader.eof()) throw new FormatException();
					context = validateFileManifest(m, list);
				} else if (messageType == FILE_CHUNK) {
					context = validateFileChunk(m, list, bytesRead);
				} else if (messageType == FILE_REQUEST) {
					if (!reader.eof()) throw new FormatException();
					context = validateFileRequest(m, list);
				} else {
					throw new InvalidMessageException();
				}
			}
			Metadata meta = metadataEncoder.encode(context.getDictionary());
			return new MessageContext(meta, context.getDependencies());
		} catch (IOException e) {
			throw new InvalidMessageException(e);
		}
	}

	private BdfMessageContext validateLegacyPrivateMessage(Message m,
			BdfList body) throws FormatException {
		// Client version 0.0: Private message text
		checkSize(body, 1);
		String text = body.getString(0);
		checkLength(text, 0, MAX_PRIVATE_MESSAGE_TEXT_LENGTH);
		// Return the metadata
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_READ, false);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validatePrivateMessage(Message m, BdfList body)
			throws FormatException {
		// Client version 0.1 to 0.2: Message type, optional private message
		// text, attachment headers.
		// Client version 0.3: Message type, optional private message text,
		// attachment headers, optional auto-delete timer.
		// Client version 0.4: As 0.3, but an attachment header may also
		// describe a chunked file (see below).
		// Client version 0.5: As 0.4, plus the link of the channel a
		// forwarded post came from.
		// Client version 0.7: As 0.5, plus the parts of the forwarded post
		// that the channel's signature covers, checked against the key in
		// the link. A link without them, as 0.5 sent, is refused: the text
		// of such a forward was the sender's, shown as the channel's.
		checkSize(body, 3, 6);
		String text = body.getOptionalString(1);
		checkLength(text, 0, MAX_PRIVATE_MESSAGE_TEXT_LENGTH);
		BdfList headers = body.getList(2);
		if (text == null) checkSize(headers, 1, MAX_ATTACHMENTS_PER_MESSAGE);
		else checkSize(headers, 0, MAX_ATTACHMENTS_PER_MESSAGE);
		for (int i = 0; i < headers.size(); i++) {
			BdfList header = headers.getList(i);
			// Image attachment: message ID, content type.
			// Shared file: manifest ID, content type, name, size.
			checkSize(header, 2, 4);
			if (header.size() == 3) throw new FormatException();
			byte[] id = header.getRaw(0);
			checkLength(id, UniqueId.LENGTH);
			String contentType = header.getString(1);
			checkLength(contentType, 1, MAX_CONTENT_TYPE_BYTES);
			if (header.size() == 4) {
				String name = header.getString(2);
				checkLength(name, 1, MAX_FILE_NAME_LENGTH);
				long size = header.getLong(3);
				if (size < 1 || size > MAX_FILE_SIZE)
					throw new FormatException();
			}
		}
		long timer = NO_AUTO_DELETE_TIMER;
		if (body.size() >= 4) {
			timer = validateAutoDeleteTimer(body.getOptionalLong(3));
		}
		// The link is only included when the message is a forward, so a
		// null in that position is not a valid encoding: a sender with
		// nothing to forward sends the shorter body instead
		String channelLink = null;
		if (body.size() == 5) throw new FormatException();
		if (body.size() == 6) {
			channelLink = body.getString(4);
			checkLength(channelLink, 1, MAX_FORWARDED_LINK_LENGTH);
			// The text is the post's, so there must be some to check
			if (text == null) throw new FormatException();
			verifyForwardedPost(channelLink, text, body.getList(5));
		}
		// Return the metadata
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_READ, false);
		meta.put(MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE);
		meta.put(MSG_KEY_HAS_TEXT, text != null);
		meta.put(MSG_KEY_ATTACHMENT_HEADERS, headers);
		if (channelLink != null) meta.put(MSG_KEY_CHANNEL_LINK, channelLink);
		if (timer != NO_AUTO_DELETE_TIMER) {
			meta.put(MSG_KEY_AUTO_DELETE_TIMER, timer);
		}
		return new BdfMessageContext(meta);
	}

	/**
	 * Checks that the text of a forward was posted to the channel its
	 * link names: the timestamp, attachment headers and signature of the
	 * post are carried with it, and the signature is checked with the
	 * key in the link over what the channel signed. Without this a
	 * forward could show any text under a real channel's name and key.
	 */
	private void verifyForwardedPost(String channelLink, String text,
			BdfList post) throws FormatException {
		// Post timestamp, optional attachment headers, signature
		checkSize(post, 3);
		long timestamp = post.getLong(0);
		if (timestamp < 0) throw new FormatException();
		BdfList headers = post.getOptionalList(1);
		if (headers != null) validateAttachmentHeaders(headers);
		byte[] sig = post.getRaw(2);
		checkLength(sig, 1, MAX_SIGNATURE_LENGTH);
		Blog blog = linkParser.parse(channelLink).blog;
		BdfList signed = getSignedPost(blog.getId(), timestamp, text, headers);
		try {
			clientHelper.verifySignature(sig, SIGNING_LABEL_POST, signed,
					blog.getAuthor().getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
	}

	private BdfMessageContext validateFileManifest(Message m, BdfList body)
			throws FormatException {
		// The file's name, type, size and chunk hashes are checked by the
		// shared file store, which the private group client uses too
		BdfDictionary meta = ChunkedFileStore.validateManifest(body);
		// Manifests aren't authenticated on their own. They're wanted only
		// once a private message references them by message ID.
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, FILE_MANIFEST);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateFileRequest(Message m, BdfList body)
			throws FormatException {
		// The manifest ID is checked by the shared file store. A request
		// has no dependencies: it is wanted by whoever holds the file,
		// whether or not the manifest is here
		BdfDictionary meta = ChunkedFileStore.validateRequest(body);
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, FILE_REQUEST);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateFileChunk(Message m, BdfList descriptor,
			long descriptorLength) throws FormatException {
		// The descriptor names the chunk's manifest and index, which the
		// shared file store checks and returns as metadata
		BdfDictionary meta = ChunkedFileStore.validateChunk(descriptor,
				m.getBody().length - descriptorLength);
		MessageId manifestId =
				new MessageId(meta.getRaw(KEY_FILE_MANIFEST_ID));
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, FILE_CHUNK);
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptorLength);
		// The chunk depends on its manifest, so it isn't delivered until
		// the manifest is, and is then checked against it. A chunk that
		// hasn't been checked is never shared with other devices.
		return new BdfMessageContext(meta, singletonList(manifestId));
	}

	private BdfMessageContext validateAttachment(Message m, BdfList descriptor,
			long descriptorLength) throws FormatException {
		// Message type, content type
		checkSize(descriptor, 2);
		String contentType = descriptor.getString(1);
		checkLength(contentType, 1, MAX_CONTENT_TYPE_BYTES);
		// Return the metadata
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, ATTACHMENT);
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptorLength);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		return new BdfMessageContext(meta);
	}
}
