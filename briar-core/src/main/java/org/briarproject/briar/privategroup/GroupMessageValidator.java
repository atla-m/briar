package org.briarproject.briar.privategroup;

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
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageContext;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.privategroup.MessageType;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory;
import org.briarproject.briar.attachment.ChunkedFileStore;
import org.briarproject.briar.attachment.CountingInputStream;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static java.util.Collections.singletonList;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.briarproject.briar.attachment.ChunkedFileStore.KEY_FILE_MANIFEST_ID;
import static org.briarproject.briar.api.privategroup.GroupMessageFactory.SIGNING_LABEL_JOIN;
import static org.briarproject.briar.api.privategroup.GroupMessageFactory.SIGNING_LABEL_POST;
import static org.briarproject.briar.api.privategroup.MessageType.ATTACHMENT;
import static org.briarproject.briar.api.privategroup.MessageType.FILE_CHUNK;
import static org.briarproject.briar.api.privategroup.MessageType.FILE_MANIFEST;
import static org.briarproject.briar.api.privategroup.MessageType.JOIN;
import static org.briarproject.briar.api.privategroup.MessageType.POST;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_NAME_LENGTH;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_POST_ATTACHMENTS;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_POST_TEXT_LENGTH;
import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory.SIGNING_LABEL_INVITE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_HAS_TEXT;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_INITIAL_JOIN_MSG;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_MEMBER;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PARENT_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PREVIOUS_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_READ;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_TIMESTAMP;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_TYPE;

@Immutable
@NotNullByDefault
class GroupMessageValidator extends BdfMessageValidator {

	private final PrivateGroupFactory privateGroupFactory;
	private final GroupInvitationFactory groupInvitationFactory;
	private final BdfReaderFactory bdfReaderFactory;

	GroupMessageValidator(PrivateGroupFactory privateGroupFactory,
			ClientHelper clientHelper, MetadataEncoder metadataEncoder,
			Clock clock, GroupInvitationFactory groupInvitationFactory,
			BdfReaderFactory bdfReaderFactory) {
		super(clientHelper, metadataEncoder, clock);
		this.privateGroupFactory = privateGroupFactory;
		this.groupInvitationFactory = groupInvitationFactory;
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
			// An attachment or file chunk is a BDF list (the descriptor)
			// followed by raw bytes, so the body can't be parsed as a single
			// list. Read the first list and check what type of message it is.
			InputStream in = new ByteArrayInputStream(m.getBody());
			CountingInputStream countIn =
					new CountingInputStream(in, MAX_MESSAGE_BODY_LENGTH);
			BdfReader reader = bdfReaderFactory.createReader(countIn, canonical);
			BdfList list = reader.readList();
			long bytesRead = countIn.getBytesRead();
			BdfMessageContext context;
			if (isType(list, ATTACHMENT)) {
				context = validateAttachment(m, list, bytesRead);
			} else if (isType(list, FILE_CHUNK)) {
				context = validateFileChunk(m, list, bytesRead);
			} else {
				// All other message types consist of a single list
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
				((Number) type).longValue() == t.getInt();
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws InvalidMessageException, FormatException {

		checkSize(body, 4, 7);

		// Message type (int)
		int type = body.getInt(0);

		// File manifests have no member, they're authenticated by the signed
		// post that references them
		if (type == FILE_MANIFEST.getInt()) return validateFileManifest(m, body);

		// Member (author)
		BdfList memberList = body.getList(1);
		Author member = clientHelper.parseAndValidateAuthor(memberList);

		BdfMessageContext c;
		if (type == JOIN.getInt()) {
			c = validateJoin(m, g, body, member);
			addMessageMetadata(c, memberList, m.getTimestamp());
		} else if (type == POST.getInt()) {
			c = validatePost(m, g, body, member);
			addMessageMetadata(c, memberList, m.getTimestamp());
		} else {
			throw new InvalidMessageException("Unknown Message Type");
		}
		c.getDictionary().put(KEY_TYPE, type);
		return c;
	}

	private BdfMessageContext validateJoin(Message m, Group g, BdfList body,
			Author member) throws FormatException {
		// Message type, member, optional invite, member's signature
		checkSize(body, 4);
		BdfList inviteList = body.getOptionalList(2);
		byte[] memberSignature = body.getRaw(3);
		checkLength(memberSignature, 1, MAX_SIGNATURE_LENGTH);

		// Invite is null if the member is the creator of the private group
		PrivateGroup pg = privateGroupFactory.parsePrivateGroup(g);
		Author creator = pg.getCreator();
		boolean isCreator = member.equals(creator);
		if (isCreator) {
			if (inviteList != null) throw new FormatException();
		} else {
			if (inviteList == null) throw new FormatException();
			// Timestamp, creator's signature
			checkSize(inviteList, 2);
			// Join timestamp must be greater than invite timestamp
			long inviteTimestamp = inviteList.getLong(0);
			if (m.getTimestamp() <= inviteTimestamp)
				throw new FormatException();
			byte[] creatorSignature = inviteList.getRaw(1);
			checkLength(creatorSignature, 1, MAX_SIGNATURE_LENGTH);
			// The invite token is signed by the creator of the private group
			BdfList token = groupInvitationFactory.createInviteToken(
					creator.getId(), member.getId(), g.getId(),
					inviteTimestamp);
			try {
				clientHelper.verifySignature(creatorSignature,
						SIGNING_LABEL_INVITE,
						token, creator.getPublicKey());
			} catch (GeneralSecurityException e) {
				throw new FormatException();
			}
		}

		// Verify the member's signature
		BdfList memberList = body.getList(1); // Already validated
		BdfList signed = BdfList.of(
				g.getId(),
				m.getTimestamp(),
				memberList,
				inviteList
		);
		try {
			clientHelper.verifySignature(memberSignature, SIGNING_LABEL_JOIN,
					signed, member.getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		// Return the metadata and no dependencies
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_INITIAL_JOIN_MSG, isCreator);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validatePost(Message m, Group g, BdfList body,
			Author member) throws FormatException {
		// Client version 0.0: Message type, member, optional parent ID,
		// previous message ID, text, signature.
		// Client version 0.1: Message type, member, optional parent ID,
		// previous message ID, optional text, attachment headers, signature.
		checkSize(body, 6, 7);
		boolean hasAttachments = body.size() == 7;
		byte[] parentId = body.getOptionalRaw(2);
		checkLength(parentId, MessageId.LENGTH);
		byte[] previousMessageId = body.getRaw(3);
		checkLength(previousMessageId, MessageId.LENGTH);
		String text;
		BdfList headers = null;
		byte[] signature;
		if (hasAttachments) {
			// Text is optional when there are attachments
			text = body.getOptionalString(4);
			checkLength(text, 1, MAX_GROUP_POST_TEXT_LENGTH);
			// The format with attachment headers is only used when there
			// are attachments or files, so the list must not be empty
			headers = body.getList(5);
			checkSize(headers, 1, MAX_GROUP_POST_ATTACHMENTS);
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
			signature = body.getRaw(6);
		} else {
			text = body.getString(4);
			checkLength(text, 1, MAX_GROUP_POST_TEXT_LENGTH);
			signature = body.getRaw(5);
		}
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);

		// Verify the member's signature. The attachment headers are covered
		// by the signature so that they can't be swapped or removed.
		BdfList memberList = body.getList(1); // Already validated
		BdfList signed = getSignedPost(g, m.getTimestamp(), memberList,
				parentId, previousMessageId, text, headers);
		try {
			clientHelper.verifySignature(signature, SIGNING_LABEL_POST,
					signed, member.getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		// The parent post, if any, and the member's previous message are
		// dependencies. Attachments are not dependencies, they may arrive
		// before or after the post that references them.
		Collection<MessageId> dependencies = new ArrayList<>();
		if (parentId != null) dependencies.add(new MessageId(parentId));
		dependencies.add(new MessageId(previousMessageId));

		// Return the metadata and dependencies
		BdfDictionary meta = new BdfDictionary();
		if (parentId != null) meta.put(KEY_PARENT_MSG_ID, parentId);
		meta.put(KEY_PREVIOUS_MSG_ID, previousMessageId);
		if (hasAttachments) {
			meta.put(KEY_HAS_TEXT, text != null);
			meta.put(KEY_ATTACHMENT_HEADERS, headers);
		}
		return new BdfMessageContext(meta, dependencies);
	}

	/**
	 * Returns the list that is signed by the author of a post. The list has
	 * the attachment headers appended if and only if the post has them, so
	 * signatures of posts in the old format remain valid.
	 */
	static BdfList getSignedPost(Group g, long timestamp, BdfList memberList,
			@Nullable byte[] parentId, byte[] previousMessageId,
			@Nullable String text, @Nullable BdfList attachmentHeaders) {
		if (attachmentHeaders == null) {
			return BdfList.of(g.getId(), timestamp, memberList, parentId,
					previousMessageId, text);
		}
		return BdfList.of(g.getId(), timestamp, memberList, parentId,
				previousMessageId, text, attachmentHeaders);
	}

	private BdfMessageContext validateFileManifest(Message m, BdfList body)
			throws FormatException {
		// The file's name, type, size and chunk hashes are checked by the
		// shared file store, which the messaging client uses too
		BdfDictionary meta = ChunkedFileStore.validateManifest(body);
		// Manifests aren't signed. They're authenticated by the signed post
		// that references them by message ID. Return the metadata and no
		// dependencies: chunks depend on the manifest, not the other way.
		meta.put(KEY_TYPE, FILE_MANIFEST.getInt());
		meta.put(KEY_TIMESTAMP, m.getTimestamp());
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
		meta.put(KEY_TIMESTAMP, m.getTimestamp());
		meta.put(KEY_TYPE, FILE_CHUNK.getInt());
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
		// Attachments aren't signed. They're authenticated by the signed
		// post that references them, which includes the attachment's message
		// ID (a hash of the attachment). Return the metadata and no
		// dependencies.
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_TYPE, ATTACHMENT.getInt());
		meta.put(KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptorLength);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		return new BdfMessageContext(meta);
	}

	private void addMessageMetadata(BdfMessageContext c, BdfList member,
			long time) {
		c.getDictionary().put(KEY_TIMESTAMP, time);
		c.getDictionary().put(KEY_READ, false);
		c.getDictionary().put(KEY_MEMBER, member);
	}

}
