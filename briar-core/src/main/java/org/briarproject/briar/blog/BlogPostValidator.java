package org.briarproject.briar.blog;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.BdfMessageContext;
import org.briarproject.bramble.api.client.BdfMessageValidator;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfReaderFactory;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.GroupFactory;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.sync.MessageContext;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogFactory;
import org.briarproject.briar.api.blog.MessageType;
import org.briarproject.briar.attachment.ChunkedFileStore;
import org.briarproject.briar.attachment.CountingInputStream;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collection;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static java.util.Collections.singletonList;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.attachment.ChunkedFileStore.KEY_FILE_MANIFEST_ID;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_AUTHOR;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_COMMENT;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_ORIGINAL_MSG_ID;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_ORIGINAL_PARENT_MSG_ID;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_PARENT_MSG_ID;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_READ;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_RSS_FEED;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_TIMESTAMP;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_TIME_RECEIVED;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_TYPE;
import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_COMMENT_TEXT_LENGTH;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_NAME_LENGTH;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_POST_ATTACHMENTS;
import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_POST_TEXT_LENGTH;
import static org.briarproject.briar.api.blog.BlogManager.CLIENT_ID;
import static org.briarproject.briar.api.blog.BlogManager.MAJOR_VERSION;
import static org.briarproject.briar.api.blog.BlogPostFactory.SIGNING_LABEL_COMMENT;
import static org.briarproject.briar.api.blog.BlogPostFactory.SIGNING_LABEL_POST;
import static org.briarproject.briar.api.blog.MessageType.COMMENT;
import static org.briarproject.briar.api.blog.MessageType.ATTACHMENT;
import static org.briarproject.briar.api.blog.MessageType.FILE_CHUNK;
import static org.briarproject.briar.api.blog.MessageType.FILE_MANIFEST;
import static org.briarproject.briar.api.blog.MessageType.POST;

@Immutable
@NotNullByDefault
class BlogPostValidator extends BdfMessageValidator {

	private final GroupFactory groupFactory;
	private final MessageFactory messageFactory;
	private final BlogFactory blogFactory;
	private final BdfReaderFactory bdfReaderFactory;

	BlogPostValidator(GroupFactory groupFactory, MessageFactory messageFactory,
			BlogFactory blogFactory, ClientHelper clientHelper,
			BdfReaderFactory bdfReaderFactory,
			MetadataEncoder metadataEncoder, Clock clock) {
		super(clientHelper, metadataEncoder, clock);

		this.groupFactory = groupFactory;
		this.messageFactory = messageFactory;
		this.blogFactory = blogFactory;
		this.bdfReaderFactory = bdfReaderFactory;
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
			// An image or file chunk is a BDF list (the descriptor)
			// followed by raw bytes, so the body can't be parsed as a
			// single list. Read the first list and see what it is.
			InputStream in = new ByteArrayInputStream(m.getBody());
			CountingInputStream countIn =
					new CountingInputStream(in, MAX_MESSAGE_BODY_LENGTH);
			BdfReader reader = bdfReaderFactory.createReader(countIn);
			BdfList list = reader.readList();
			long bytesRead = countIn.getBytesRead();
			BdfMessageContext context;
			if (isType(list, ATTACHMENT)) {
				context = validateAttachment(m, list, bytesRead);
			} else if (isType(list, FILE_CHUNK)) {
				context = validateFileChunk(m, list, bytesRead);
			} else {
				// Every other type is a single list
				if (!reader.eof()) throw new FormatException();
				context = validateMessage(m, g, list);
			}
			Metadata meta = metadataEncoder.encode(context.getDictionary());
			return new MessageContext(meta, context.getDependencies());
		} catch (IOException e) {
			throw new InvalidMessageException(e);
		}
	}

	private boolean isType(BdfList list, MessageType t)
			throws FormatException {
		if (list.isEmpty()) throw new FormatException();
		Object type = list.get(0);
		return type instanceof Number &&
				((Number) type).intValue() == t.getInt();
	}

	private BdfMessageContext validateAttachment(Message m, BdfList descriptor,
			long descriptorLength) throws FormatException {
		// Message type, content type
		checkSize(descriptor, 2);
		String contentType = descriptor.getString(1);
		checkLength(contentType, 1, MAX_CONTENT_TYPE_BYTES);
		// An image isn't signed. It's authenticated by the signed post
		// that references it, which covers its message ID, a hash of the
		// image itself.
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_TYPE, ATTACHMENT.getInt());
		meta.put(KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptorLength);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateFileManifest(Message m, BdfList body)
			throws FormatException {
		// The file's name, type, size and chunk hashes are checked by the
		// shared file store, which the other clients use too
		BdfDictionary meta = ChunkedFileStore.validateManifest(body);
		meta.put(KEY_TYPE, FILE_MANIFEST.getInt());
		meta.put(KEY_TIMESTAMP, m.getTimestamp());
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateFileChunk(Message m, BdfList descriptor,
			long descriptorLength) throws FormatException {
		BdfDictionary meta = ChunkedFileStore.validateChunk(descriptor,
				m.getBody().length - descriptorLength);
		MessageId manifestId =
				new MessageId(meta.getRaw(KEY_FILE_MANIFEST_ID));
		meta.put(KEY_TYPE, FILE_CHUNK.getInt());
		meta.put(KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptorLength);
		// The chunk depends on its manifest, so it isn't delivered until
		// the manifest is, and is then checked against it
		return new BdfMessageContext(meta, singletonList(manifestId));
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws InvalidMessageException, FormatException {

		BdfMessageContext c;

		int type = body.getInt(0);
		body.remove(0);
		switch (MessageType.valueOf(type)) {
			case POST:
				c = validatePost(m, g, body);
				addMessageMetadata(c, m.getTimestamp());
				break;
			case COMMENT:
				c = validateComment(m, g, body);
				addMessageMetadata(c, m.getTimestamp());
				break;
			case WRAPPED_POST:
				c = validateWrappedPost(body);
				break;
			case WRAPPED_COMMENT:
				c = validateWrappedComment(body);
				break;
			case FILE_MANIFEST:
				// Put the type back: the store reads the whole body
				body.add(0, FILE_MANIFEST.getInt());
				c = validateFileManifest(m, body);
				break;
			default:
				throw new InvalidMessageException("Unknown Message Type");
		}
		c.getDictionary().put(KEY_TYPE, type);
		return c;
	}

	private BdfMessageContext validatePost(Message m, Group g, BdfList body)
			throws InvalidMessageException, FormatException {

		// Client version 0.1: text, signature.
		// Client version 0.2: optional text, attachment headers, signature.
		checkSize(body, 2, 3);
		boolean hasAttachments = body.size() == 3;
		String text;
		BdfList headers = null;
		byte[] sig;
		if (hasAttachments) {
			// Text is optional when there are attachments
			text = body.getOptionalString(0);
			checkLength(text, 1, MAX_BLOG_POST_TEXT_LENGTH);
			headers = validateAttachmentHeaders(body.getList(1));
			sig = body.getRaw(2);
		} else {
			text = body.getString(0);
			checkLength(text, 0, MAX_BLOG_POST_TEXT_LENGTH);
			sig = body.getRaw(1);
		}

		// Verify signature. The attachment headers are covered by it, so
		// they can't be swapped or removed.
		checkLength(sig, 1, MAX_SIGNATURE_LENGTH);
		BdfList signed = getSignedPost(g.getId(), m.getTimestamp(), text,
				headers);
		Blog b = blogFactory.parseBlog(g);
		Author a = b.getAuthor();
		try {
			clientHelper.verifySignature(sig, SIGNING_LABEL_POST, signed,
					a.getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new InvalidMessageException(e);
		}

		// Return the metadata and dependencies
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_ORIGINAL_MSG_ID, m.getId());
		meta.put(KEY_AUTHOR, clientHelper.toList(a));
		meta.put(KEY_RSS_FEED, b.isRssFeed());
		if (headers != null) meta.put(KEY_ATTACHMENT_HEADERS, headers);
		return new BdfMessageContext(meta);
	}

	/**
	 * Checks the headers of the images and files a post carries. An image
	 * is named by the message holding it; a file by the message holding
	 * its manifest, with the name and size shown before it has arrived.
	 */
	private BdfList validateAttachmentHeaders(BdfList headers)
			throws FormatException {
		// The format with headers is only used when there are some
		checkSize(headers, 1, MAX_BLOG_POST_ATTACHMENTS);
		for (int i = 0; i < headers.size(); i++) {
			BdfList header = headers.getList(i);
			// Image: message ID, content type.
			// File: manifest ID, content type, name, size.
			checkSize(header, 2, 4);
			if (header.size() == 3) throw new FormatException();
			checkLength(header.getRaw(0), UniqueId.LENGTH);
			checkLength(header.getString(1), 1, MAX_CONTENT_TYPE_BYTES);
			if (header.size() == 4) {
				checkLength(header.getString(2), 1, MAX_FILE_NAME_LENGTH);
				long size = header.getLong(3);
				if (size < 1 || size > MAX_FILE_SIZE)
					throw new FormatException();
			}
		}
		return headers;
	}

	/**
	 * Returns the list a post's signature covers. Shared with the post
	 * factory so that both sides sign and check the same thing.
	 */
	static BdfList getSignedPost(GroupId groupId, long timestamp,
			@Nullable String text, @Nullable BdfList attachmentHeaders) {
		if (attachmentHeaders == null) {
			return BdfList.of(groupId, timestamp, text);
		}
		return BdfList.of(groupId, timestamp, text, attachmentHeaders);
	}

	private BdfMessageContext validateComment(Message m, Group g, BdfList body)
			throws InvalidMessageException, FormatException {

		// Comment, parent original ID, parent ID, signature
		checkSize(body, 4);

		// Comment
		String comment = body.getOptionalString(0);
		checkLength(comment, 1, MAX_BLOG_COMMENT_TEXT_LENGTH);

		// Parent original ID
		// The ID of a post or comment in this blog or another blog
		byte[] pOriginalIdBytes = body.getRaw(1);
		checkLength(pOriginalIdBytes, MessageId.LENGTH);
		MessageId pOriginalId = new MessageId(pOriginalIdBytes);

		// Parent ID
		// The ID of the comment's parent, which is a post, comment, wrapped
		// post or wrapped comment in this blog, which had the ID
		// parentOriginalId in the blog where it was originally posted
		byte[] currentIdBytes = body.getRaw(2);
		checkLength(currentIdBytes, MessageId.LENGTH);
		MessageId currentId = new MessageId(currentIdBytes);

		// Signature
		byte[] sig = body.getRaw(3);
		checkLength(sig, 1, MAX_SIGNATURE_LENGTH);
		BdfList signed = BdfList.of(g.getId(), m.getTimestamp(), comment,
				pOriginalId, currentId);
		Blog b = blogFactory.parseBlog(g);
		Author a = b.getAuthor();
		try {
			clientHelper.verifySignature(sig, SIGNING_LABEL_COMMENT,
					signed, a.getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new InvalidMessageException(e);
		}

		// Return the metadata and dependencies
		BdfDictionary meta = new BdfDictionary();
		if (comment != null) meta.put(KEY_COMMENT, comment);
		meta.put(KEY_ORIGINAL_MSG_ID, m.getId());
		meta.put(KEY_ORIGINAL_PARENT_MSG_ID, pOriginalId);
		meta.put(KEY_PARENT_MSG_ID, currentId);
		meta.put(KEY_AUTHOR, clientHelper.toList(a));
		Collection<MessageId> dependencies = singletonList(currentId);
		return new BdfMessageContext(meta, dependencies);
	}

	private BdfMessageContext validateWrappedPost(BdfList body)
			throws InvalidMessageException, FormatException {

		// Copied group descriptor, copied timestamp, copied text, copied
		// signature
		checkSize(body, 4);

		// Copied group descriptor of original post
		byte[] descriptor = body.getRaw(0);

		// Copied timestamp of original post
		long wTimestamp = body.getLong(1);
		if (wTimestamp < 0) throw new FormatException();

		// Copied text of original post
		String text = body.getString(2);
		checkLength(text, 0, MAX_BLOG_POST_TEXT_LENGTH);

		// Copied signature of original post
		byte[] signature = body.getRaw(3);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);

		// Reconstruct and validate the original post
		Group wGroup = groupFactory.createGroup(CLIENT_ID, MAJOR_VERSION,
				descriptor);
		Blog wBlog = blogFactory.parseBlog(wGroup);
		BdfList wBodyList = BdfList.of(POST.getInt(), text, signature);
		byte[] wBody = clientHelper.toByteArray(wBodyList);
		Message wMessage =
				messageFactory.createMessage(wGroup.getId(), wTimestamp, wBody);
		wBodyList.remove(0);
		BdfMessageContext c = validatePost(wMessage, wGroup, wBodyList);

		// Return the metadata and dependencies
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_ORIGINAL_MSG_ID, wMessage.getId());
		meta.put(KEY_TIMESTAMP, wTimestamp);
		meta.put(KEY_AUTHOR, c.getDictionary().getList(KEY_AUTHOR));
		meta.put(KEY_RSS_FEED, wBlog.isRssFeed());
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateWrappedComment(BdfList body)
			throws InvalidMessageException, FormatException {

		// Copied group descriptor, copied timestamp, copied text, copied
		// parent original ID, copied parent ID, copied signature, parent ID
		checkSize(body, 7);

		// Copied group descriptor of original comment
		byte[] descriptor = body.getRaw(0);

		// Copied timestamp of original comment
		long wTimestamp = body.getLong(1);
		if (wTimestamp < 0) throw new FormatException();

		// Copied text of original comment
		String comment = body.getOptionalString(2);
		checkLength(comment, 1, MAX_BLOG_COMMENT_TEXT_LENGTH);

		// Copied parent original ID of original comment
		byte[] pOriginalIdBytes = body.getRaw(3);
		checkLength(pOriginalIdBytes, MessageId.LENGTH);
		MessageId pOriginalId = new MessageId(pOriginalIdBytes);

		// Copied parent ID of original comment
		byte[] oldIdBytes = body.getRaw(4);
		checkLength(oldIdBytes, MessageId.LENGTH);
		MessageId oldId = new MessageId(oldIdBytes);

		// Copied signature of original comment
		byte[] signature = body.getRaw(5);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);

		// Parent ID
		// The ID of this comment's parent, which is a post, comment, wrapped
		// post or wrapped comment in this blog, which had the ID
		// copiedParentOriginalId in the blog where the parent was originally
		// posted, and the ID copiedParentId in the blog where this comment was
		// originally posted
		byte[] parentIdBytes = body.getRaw(6);
		checkLength(parentIdBytes, MessageId.LENGTH);
		MessageId parentId = new MessageId(parentIdBytes);

		// Reconstruct and validate the original comment
		Group wGroup = groupFactory.createGroup(CLIENT_ID, MAJOR_VERSION,
				descriptor);
		BdfList wBodyList = BdfList.of(COMMENT.getInt(), comment, pOriginalId,
				oldId, signature);
		byte[] wBody = clientHelper.toByteArray(wBodyList);
		Message wMessage =
				messageFactory.createMessage(wGroup.getId(), wTimestamp, wBody);
		wBodyList.remove(0);
		BdfMessageContext c = validateComment(wMessage, wGroup, wBodyList);

		// Return the metadata and dependencies
		Collection<MessageId> dependencies = singletonList(parentId);
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_ORIGINAL_MSG_ID, wMessage.getId());
		meta.put(KEY_ORIGINAL_PARENT_MSG_ID, pOriginalId);
		meta.put(KEY_PARENT_MSG_ID, parentId);
		meta.put(KEY_TIMESTAMP, wTimestamp);
		if (comment != null) meta.put(KEY_COMMENT, comment);
		meta.put(KEY_AUTHOR, c.getDictionary().getList(KEY_AUTHOR));
		return new BdfMessageContext(meta, dependencies);
	}

	private void addMessageMetadata(BdfMessageContext c, long time) {
		c.getDictionary().put(KEY_TIMESTAMP, time);
		c.getDictionary().put(KEY_TIME_RECEIVED, clock.currentTimeMillis());
		c.getDictionary().put(KEY_READ, false);
	}

}
