package org.briarproject.briar.privategroup;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.BdfIncomingMessageHook;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.client.MessageTracker.GroupCount;
import org.briarproject.briar.api.client.ProtocolStateException;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorInfo.Status;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.privategroup.GroupFileHeader;
import org.briarproject.briar.api.privategroup.GroupFileStatus;
import org.briarproject.briar.api.privategroup.GroupMember;
import org.briarproject.briar.api.privategroup.GroupMessage;
import org.briarproject.briar.api.privategroup.GroupMessageHeader;
import org.briarproject.briar.api.privategroup.JoinMessageHeader;
import org.briarproject.briar.api.privategroup.MessageType;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.Visibility;
import org.briarproject.briar.api.privategroup.event.ContactRelationshipRevealedEvent;
import org.briarproject.briar.api.privategroup.event.GroupAttachmentReceivedEvent;
import org.briarproject.briar.api.privategroup.event.GroupDissolvedEvent;
import org.briarproject.briar.api.privategroup.event.GroupFileProgressEvent;
import org.briarproject.briar.api.privategroup.event.GroupMessageAddedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.Collections.emptyList;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_SHARE;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.bramble.util.StringUtils.utf8IsTooLong;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.briarproject.briar.api.identity.AuthorInfo.Status.UNVERIFIED;
import static org.briarproject.briar.api.identity.AuthorInfo.Status.VERIFIED;
import static org.briarproject.briar.api.privategroup.MessageType.ATTACHMENT;
import static org.briarproject.briar.api.privategroup.MessageType.FILE_CHUNK;
import static org.briarproject.briar.api.privategroup.MessageType.FILE_MANIFEST;
import static org.briarproject.briar.api.privategroup.MessageType.JOIN;
import static org.briarproject.briar.api.privategroup.MessageType.POST;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.FILE_CHUNK_PAYLOAD_LENGTH;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_FILE_NAME_LENGTH;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_FILE_SIZE;
import static org.briarproject.briar.api.privategroup.Visibility.INVISIBLE;
import static org.briarproject.briar.api.privategroup.Visibility.REVEALED_BY_CONTACT;
import static org.briarproject.briar.api.privategroup.Visibility.REVEALED_BY_US;
import static org.briarproject.briar.api.privategroup.Visibility.VISIBLE;
import static org.briarproject.briar.privategroup.GroupConstants.GROUP_KEY_CREATOR_ID;
import static org.briarproject.briar.privategroup.GroupConstants.GROUP_KEY_DISSOLVED;
import static org.briarproject.briar.privategroup.GroupConstants.GROUP_KEY_MEMBERS;
import static org.briarproject.briar.privategroup.GroupConstants.GROUP_KEY_OUR_GROUP;
import static org.briarproject.briar.privategroup.GroupConstants.GROUP_KEY_VISIBILITY;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FILE_CHUNK_IDS;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FILE_CONTENT_TYPE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FILE_NAME;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FILE_SIZE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FILE_VALID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_HAS_TEXT;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_INITIAL_JOIN_MSG;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_MEMBER;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PARENT_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PREVIOUS_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_READ;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_TIMESTAMP;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_TYPE;
import static org.briarproject.briar.privategroup.GroupConstants.MISSING_ATTACHMENT_CLEANUP_DURATION_MS;

@ThreadSafe
@NotNullByDefault
class PrivateGroupManagerImpl extends BdfIncomingMessageHook
		implements PrivateGroupManager {

	private final PrivateGroupFactory privateGroupFactory;
	private final ContactManager contactManager;
	private final IdentityManager identityManager;
	private final AuthorManager authorManager;
	private final MessageTracker messageTracker;
	private final List<PrivateGroupHook> hooks;

	@Inject
	PrivateGroupManagerImpl(ClientHelper clientHelper,
			MetadataParser metadataParser, DatabaseComponent db,
			PrivateGroupFactory privateGroupFactory,
			ContactManager contactManager, IdentityManager identityManager,
			AuthorManager authorManager, MessageTracker messageTracker) {
		super(db, clientHelper, metadataParser);
		this.privateGroupFactory = privateGroupFactory;
		this.contactManager = contactManager;
		this.identityManager = identityManager;
		this.authorManager = authorManager;
		this.messageTracker = messageTracker;
		hooks = new CopyOnWriteArrayList<>();
	}

	@Override
	public void addPrivateGroup(PrivateGroup group, GroupMessage joinMsg,
			boolean creator) throws DbException {
		Transaction txn = db.startTransaction(false);
		try {
			addPrivateGroup(txn, group, joinMsg, creator);
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public void addPrivateGroup(Transaction txn, PrivateGroup group,
			GroupMessage joinMsg, boolean creator) throws DbException {
		try {
			db.addGroup(txn, group.getGroup());
			AuthorId creatorId = group.getCreator().getId();
			BdfDictionary meta = BdfDictionary.of(
					new BdfEntry(GROUP_KEY_MEMBERS, new BdfList()),
					new BdfEntry(GROUP_KEY_CREATOR_ID, creatorId),
					new BdfEntry(GROUP_KEY_OUR_GROUP, creator),
					new BdfEntry(GROUP_KEY_DISSOLVED, false)
			);
			clientHelper.mergeGroupMetadata(txn, group.getId(), meta);
			joinPrivateGroup(txn, joinMsg, creator);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void joinPrivateGroup(Transaction txn, GroupMessage m,
			boolean creator) throws DbException, FormatException {
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_TYPE, JOIN.getInt());
		meta.put(KEY_INITIAL_JOIN_MSG, creator);
		addMessageMetadata(meta, m);
		clientHelper.addLocalMessage(txn, m.getMessage(), meta, true, false);
		messageTracker.trackOutgoingMessage(txn, m.getMessage());
		addMember(txn, m.getMessage().getGroupId(), m.getMember(), VISIBLE);
		setPreviousMsgId(txn, m.getMessage().getGroupId(),
				m.getMessage().getId());
		attachJoinMessageAddedEvent(txn, m.getMessage(), meta, true);
	}

	@Override
	public void removePrivateGroup(Transaction txn, GroupId g)
			throws DbException {
		for (PrivateGroupHook hook : hooks) {
			hook.removingGroup(txn, g);
		}
		Group group = db.getGroup(txn, g);
		db.removeGroup(txn, group);
	}

	@Override
	public void removePrivateGroup(GroupId g) throws DbException {
		db.transaction(false, txn -> removePrivateGroup(txn, g));
	}

	@Override
	public MessageId getPreviousMsgId(GroupId g) throws DbException {
		return db.transactionWithResult(true,
				txn -> getPreviousMsgId(txn, g));
	}

	public MessageId getPreviousMsgId(Transaction txn, GroupId g)
			throws DbException {
		try {
			BdfDictionary d = clientHelper.getGroupMetadataAsDictionary(txn, g);
			byte[] previousMsgIdBytes = d.getRaw(KEY_PREVIOUS_MSG_ID);
			return new MessageId(previousMsgIdBytes);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void setPreviousMsgId(Transaction txn, GroupId g,
			MessageId previousMsgId) throws DbException, FormatException {
		BdfDictionary d = BdfDictionary
				.of(new BdfEntry(KEY_PREVIOUS_MSG_ID, previousMsgId));
		clientHelper.mergeGroupMetadata(txn, g, d);
	}

	@Override
	public void markGroupDissolved(Transaction txn, GroupId g)
			throws DbException {
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(GROUP_KEY_DISSOLVED, true)
		);
		try {
			clientHelper.mergeGroupMetadata(txn, g, meta);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		Event e = new GroupDissolvedEvent(g);
		txn.attach(e);
	}

	@Override
	public GroupMessageHeader addLocalMessage(GroupMessage m)
			throws DbException {
		return db.transactionWithResult(false, txn -> addLocalMessage(txn, m));
	}

	@Override
	public GroupMessageHeader addLocalMessage(Transaction txn, GroupMessage m)
			throws DbException {
		try {
			// store message and metadata
			BdfDictionary meta = new BdfDictionary();
			meta.put(KEY_TYPE, POST.getInt());
			if (m.getParent() != null)
				meta.put(KEY_PARENT_MSG_ID, m.getParent());
			addMessageMetadata(meta, m);
			List<AttachmentHeader> attachments = m.getAttachmentHeaders();
			List<GroupFileHeader> files = m.getFileHeaders();
			if (!attachments.isEmpty() || !files.isEmpty()) {
				meta.put(KEY_HAS_TEXT, m.hasText());
				meta.put(KEY_ATTACHMENT_HEADERS,
						encodeAttachmentHeaders(attachments, files));
				// Mark the attachments as shared and permanent now that
				// we're ready to send the post that references them. An
				// attachment entry may point at a chunked image's manifest,
				// in which case its chunks are shared too.
				Set<MessageId> referenced = new HashSet<>();
				for (AttachmentHeader a : attachments)
					referenced.add(a.getMessageId());
				for (GroupFileHeader h : files) referenced.add(h.getManifestId());
				for (MessageId id : referenced) {
					db.setMessageShared(txn, id);
					db.setMessagePermanent(txn, id);
					if (isManifest(txn, id)) {
						for (MessageId chunkId : getChunkIds(txn, id)) {
							db.setMessageShared(txn, chunkId);
							db.setMessagePermanent(txn, chunkId);
						}
					}
				}
			}
			GroupId g = m.getMessage().getGroupId();
			clientHelper
					.addLocalMessage(txn, m.getMessage(), meta, true, false);
			// track message
			setPreviousMsgId(txn, g, m.getMessage().getId());
			messageTracker.trackOutgoingMessage(txn, m.getMessage());
			// broadcast event
			attachGroupMessageAddedEvent(txn, m.getMessage(), meta, true);
			AuthorInfo authorInfo = authorManager.getMyAuthorInfo(txn);
			return new GroupMessageHeader(m.getMessage().getGroupId(),
					m.getMessage().getId(), m.getParent(),
					m.getMessage().getTimestamp(), m.getMember(), authorInfo,
					true, m.hasText(), attachments, files);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void addMessageMetadata(BdfDictionary meta, GroupMessage m) {
		meta.put(KEY_MEMBER, clientHelper.toList(m.getMember()));
		meta.put(KEY_TIMESTAMP, m.getMessage().getTimestamp());
		meta.put(KEY_READ, true);
	}

	@Override
	public AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream in)
			throws DbException, IOException {
		// The attachment is a BDF descriptor followed by the raw bytes, and
		// the whole thing must fit into a single message
		ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
		byte[] descriptor = clientHelper.toByteArray(
				BdfList.of(ATTACHMENT.getInt(), contentType));
		bodyOut.write(descriptor);
		copyAndClose(in, bodyOut);
		if (bodyOut.size() > MAX_MESSAGE_BODY_LENGTH)
			throw new FileTooBigException();
		byte[] body = bodyOut.toByteArray();
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_TYPE, ATTACHMENT.getInt());
		meta.put(KEY_TIMESTAMP, timestamp);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptor.length);
		Message m = clientHelper.createMessage(groupId, timestamp, body);
		// Attachments are temporary and not shared until the post that
		// references them is added
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, false, true));
		return new AttachmentHeader(groupId, m.getId(), contentType);
	}

	@Override
	public void removeAttachment(AttachmentHeader header) throws DbException {
		db.transaction(false,
				txn -> db.removeMessage(txn, header.getMessageId()));
	}

	private BdfList encodeAttachmentHeaders(List<AttachmentHeader> headers,
			List<GroupFileHeader> files) {
		BdfList list = new BdfList();
		for (AttachmentHeader a : headers) {
			list.add(BdfList.of(a.getMessageId(), a.getContentType()));
		}
		for (GroupFileHeader h : files) {
			list.add(BdfList.of(h.getManifestId(), h.getContentType(),
					h.getName(), h.getSize()));
		}
		return list;
	}

	/**
	 * Parses the image attachments of a post, which are the two-element
	 * entries of its attachment header list.
	 */
	private List<AttachmentHeader> parseAttachmentHeaders(GroupId g,
			BdfDictionary meta) throws FormatException {
		if (!meta.containsKey(KEY_ATTACHMENT_HEADERS)) return emptyList();
		BdfList list = meta.getList(KEY_ATTACHMENT_HEADERS);
		List<AttachmentHeader> headers = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			BdfList header = list.getList(i);
			if (header.size() != 2) continue;
			MessageId m = new MessageId(header.getRaw(0));
			String contentType = header.getString(1);
			headers.add(new AttachmentHeader(g, m, contentType));
		}
		return headers;
	}

	/**
	 * Parses the shared files of a post, which are the four-element entries
	 * of its attachment header list.
	 */
	private List<GroupFileHeader> parseFileHeaders(GroupId g,
			BdfDictionary meta) throws FormatException {
		if (!meta.containsKey(KEY_ATTACHMENT_HEADERS)) return emptyList();
		BdfList list = meta.getList(KEY_ATTACHMENT_HEADERS);
		List<GroupFileHeader> headers = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			BdfList header = list.getList(i);
			if (header.size() != 4) continue;
			MessageId manifestId = new MessageId(header.getRaw(0));
			String contentType = header.getString(1);
			String name = header.getString(2);
			long size = header.getLong(3);
			headers.add(new GroupFileHeader(g, manifestId, name, contentType,
					size));
		}
		return headers;
	}

	// Shared files. A file is split into chunks that each fit into one
	// message, plus a manifest listing the chunks. Chunks and manifest are
	// ordinary group messages, so they're forwarded by every member over any
	// transport and a file can arrive piece by piece across many contacts.

	@Override
	public GroupFileHeader addLocalFile(GroupId groupId, long timestamp,
			String name, String contentType, InputStream in)
			throws DbException, IOException {
		if (name.isEmpty() || utf8IsTooLong(name, MAX_FILE_NAME_LENGTH))
			throw new IllegalArgumentException();
		if (contentType.isEmpty() ||
				utf8IsTooLong(contentType, MAX_CONTENT_TYPE_BYTES)) {
			throw new IllegalArgumentException();
		}
		// Read the file in chunk-sized pieces and store each chunk as a
		// temporary, unshared message. The manifest gets the given timestamp
		// and the chunks the following ones, so they're sent in order.
		byte[] descriptor = clientHelper.toByteArray(
				BdfList.of(FILE_CHUNK.getInt()));
		List<MessageId> chunkIds = new ArrayList<>();
		long size = 0;
		try {
			byte[] buf = new byte[FILE_CHUNK_PAYLOAD_LENGTH];
			while (true) {
				int read = readFully(in, buf);
				if (read <= 0) break;
				size += read;
				if (size > MAX_GROUP_FILE_SIZE) throw new FileTooBigException();
				byte[] body = new byte[descriptor.length + read];
				System.arraycopy(descriptor, 0, body, 0, descriptor.length);
				System.arraycopy(buf, 0, body, descriptor.length, read);
				long chunkTimestamp = timestamp + 1 + chunkIds.size();
				Message m = clientHelper.createMessage(groupId, chunkTimestamp,
						body);
				BdfDictionary meta = new BdfDictionary();
				meta.put(KEY_TYPE, FILE_CHUNK.getInt());
				meta.put(KEY_TIMESTAMP, chunkTimestamp);
				meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptor.length);
				db.transaction(false, txn ->
						clientHelper.addLocalMessage(txn, m, meta, false, true));
				chunkIds.add(m.getId());
				if (read < buf.length) break; // Last chunk
			}
			if (size == 0) throw new IllegalArgumentException("Empty file");
		} catch (IOException | RuntimeException e) {
			// Don't leave orphaned chunks behind
			db.transaction(false, txn -> {
				for (MessageId id : chunkIds) db.removeMessage(txn, id);
			});
			throw e;
		} finally {
			in.close();
		}
		// Store the manifest
		BdfList chunkIdList = new BdfList();
		for (MessageId id : chunkIds) chunkIdList.add(id);
		BdfList body = BdfList.of(FILE_MANIFEST.getInt(), name, contentType,
				size, chunkIdList);
		Message manifest = clientHelper.createMessage(groupId, timestamp, body);
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_TYPE, FILE_MANIFEST.getInt());
		meta.put(KEY_TIMESTAMP, timestamp);
		meta.put(KEY_FILE_NAME, name);
		meta.put(KEY_FILE_CONTENT_TYPE, contentType);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(KEY_FILE_SIZE, size);
		meta.put(KEY_FILE_CHUNK_IDS, chunkIdList);
		meta.put(KEY_FILE_VALID, true); // We created the chunks ourselves
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, manifest, meta, false, true));
		return new GroupFileHeader(groupId, manifest.getId(), name,
				contentType, size);
	}

	/**
	 * Reads from the stream until the buffer is full or the stream ends.
	 * Returns the number of bytes read, or -1 if the stream had ended.
	 */
	private int readFully(InputStream in, byte[] buf) throws IOException {
		int total = 0;
		while (total < buf.length) {
			int read = in.read(buf, total, buf.length - total);
			if (read == -1) break;
			total += read;
		}
		return total == 0 ? -1 : total;
	}

	@Override
	public void removeFile(GroupFileHeader header) throws DbException {
		db.transaction(false, txn -> {
			for (MessageId id : getChunkIds(txn, header.getManifestId())) {
				db.removeMessage(txn, id);
			}
			db.removeMessage(txn, header.getManifestId());
		});
	}

	private List<MessageId> getChunkIds(Transaction txn, MessageId manifestId)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, manifestId);
			return parseChunkIds(meta);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private List<MessageId> parseChunkIds(BdfDictionary manifestMeta)
			throws FormatException {
		BdfList list = manifestMeta.getList(KEY_FILE_CHUNK_IDS);
		List<MessageId> ids = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			ids.add(new MessageId(list.getRaw(i)));
		}
		return ids;
	}

	@Override
	public GroupFileStatus getFileStatus(GroupFileHeader header)
			throws DbException {
		return db.transactionWithResult(true,
				txn -> getFileStatus(txn, header));
	}

	@Override
	public GroupFileStatus getFileStatus(Transaction txn,
			GroupFileHeader header) throws DbException {
		try {
			BdfDictionary manifestMeta;
			try {
				manifestMeta = clientHelper.getMessageMetadataAsDictionary(txn,
						header.getManifestId());
			} catch (NoSuchMessageException e) {
				// Chunks may have arrived, but without the manifest we can't
				// tell which of them belong to this file
				return new GroupFileStatus(header, false, 0);
			}
			List<MessageId> chunkIds = parseChunkIds(manifestMeta);
			int received = countChunks(txn, header.getGroupId(), chunkIds);
			if (received == header.getChunkCount()) {
				// If all chunks are here but they don't add up to the
				// declared size, the file is unusable. Report it as
				// incomplete. The check is normally recorded when the last
				// chunk arrives, but compute it here if it wasn't.
				Boolean valid = manifestMeta.getOptionalBoolean(KEY_FILE_VALID);
				if (valid == null) {
					valid = chunksAddUpToSize(txn, chunkIds,
							manifestMeta.getLong(KEY_FILE_SIZE), null);
				}
				if (!valid) received--;
			}
			return new GroupFileStatus(header, true, received);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	/**
	 * Returns how many of the given chunks have been received.
	 */
	private int countChunks(Transaction txn, GroupId g,
			List<MessageId> chunkIds) throws DbException {
		Set<MessageId> present = new HashSet<>(getChunkIdsInGroup(txn, g));
		int count = 0;
		for (MessageId id : chunkIds) if (present.contains(id)) count++;
		return count;
	}

	private Collection<MessageId> getChunkIdsInGroup(Transaction txn,
			GroupId g) throws DbException {
		try {
			BdfDictionary query = BdfDictionary.of(
					new BdfEntry(KEY_TYPE, FILE_CHUNK.getInt()));
			return clientHelper.getMessageIds(txn, g, query);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public InputStream getFile(GroupFileHeader header) throws DbException {
		return db.transactionWithResult(true, txn -> getFile(txn, header));
	}

	@Override
	public InputStream getFile(Transaction txn, GroupFileHeader header)
			throws DbException {
		// Check that the file is complete and valid before handing out a
		// stream, so callers don't get a truncated file
		GroupFileStatus status = getFileStatus(txn, header);
		if (!status.isComplete()) throw new NoSuchMessageException();
		List<MessageId> chunkIds = getChunkIds(txn, header.getManifestId());
		return new ChunkInputStream(header.getGroupId(), chunkIds);
	}

	@Override
	public GroupFileHeader getFileHeader(GroupId groupId, MessageId manifestId)
			throws DbException {
		return db.transactionWithResult(true,
				txn -> getFileHeader(txn, groupId, manifestId));
	}

	@Override
	public GroupFileHeader getFileHeader(Transaction txn, GroupId groupId,
			MessageId manifestId) throws DbException {
		try {
			Message m = db.getMessage(txn, manifestId);
			// Don't let a manifest be read in the context of another group
			if (!m.getGroupId().equals(groupId))
				throw new NoSuchMessageException();
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, manifestId);
			if (meta.getInt(KEY_TYPE) != FILE_MANIFEST.getInt())
				throw new NoSuchMessageException();
			return new GroupFileHeader(groupId, manifestId,
					meta.getString(KEY_FILE_NAME),
					meta.getString(KEY_FILE_CONTENT_TYPE),
					meta.getLong(KEY_FILE_SIZE));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	/**
	 * Returns true if the given message is a file manifest.
	 */
	private boolean isManifest(Transaction txn, MessageId m)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, m);
			return meta.getInt(KEY_TYPE) == FILE_MANIFEST.getInt();
		} catch (NoSuchMessageException e) {
			return false;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	/**
	 * Returns the IDs of all messages referenced by a post's attachment
	 * header list: single-message attachments and file manifests alike. A
	 * post may reference a chunked image as a plain attachment entry, so
	 * manifests can appear in either kind of entry.
	 */
	private Set<MessageId> getReferencedIds(BdfDictionary postMeta)
			throws FormatException {
		Set<MessageId> ids = new HashSet<>();
		if (!postMeta.containsKey(KEY_ATTACHMENT_HEADERS)) return ids;
		BdfList list = postMeta.getList(KEY_ATTACHMENT_HEADERS);
		for (int i = 0; i < list.size(); i++) {
			ids.add(new MessageId(list.getList(i).getRaw(0)));
		}
		return ids;
	}

	/**
	 * Reads a file's chunks from the database one at a time, in order.
	 */
	private class ChunkInputStream extends InputStream {

		private final GroupId groupId;
		private final List<MessageId> chunkIds;
		private int next = 0;
		private InputStream current = new ByteArrayInputStream(new byte[0]);

		private ChunkInputStream(GroupId groupId, List<MessageId> chunkIds) {
			this.groupId = groupId;
			this.chunkIds = chunkIds;
		}

		@Override
		public int read() throws IOException {
			byte[] b = new byte[1];
			int read = read(b, 0, 1);
			return read == -1 ? -1 : b[0] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			while (true) {
				int read = current.read(b, off, len);
				if (read != -1) return read;
				if (next >= chunkIds.size()) return -1;
				current = loadChunk(chunkIds.get(next++));
			}
		}

		private InputStream loadChunk(MessageId id) throws IOException {
			try {
				return db.transactionWithResult(true, txn -> {
					Message m = db.getMessage(txn, id);
					// Check the chunk belongs to this group, so a manifest
					// can't be used to read messages from other groups
					if (!m.getGroupId().equals(groupId))
						throw new NoSuchMessageException();
					BdfDictionary meta =
							clientHelper.getMessageMetadataAsDictionary(txn, id);
					int offset = meta.getInt(MSG_KEY_DESCRIPTOR_LENGTH);
					byte[] body = m.getBody();
					return new ByteArrayInputStream(body, offset,
							body.length - offset);
				});
			} catch (DbException e) {
				throw new IOException(e);
			}
		}
	}

	@Override
	public PrivateGroup getPrivateGroup(GroupId g) throws DbException {
		PrivateGroup privateGroup;
		Transaction txn = db.startTransaction(true);
		try {
			privateGroup = getPrivateGroup(txn, g);
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
		return privateGroup;
	}

	@Override
	public PrivateGroup getPrivateGroup(Transaction txn, GroupId g)
			throws DbException {
		try {
			Group group = db.getGroup(txn, g);
			return privateGroupFactory.parsePrivateGroup(group);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public Collection<PrivateGroup> getPrivateGroups(Transaction txn)
			throws DbException {
		Collection<Group> groups = db.getGroups(txn, CLIENT_ID, MAJOR_VERSION);
		Collection<PrivateGroup> privateGroups = new ArrayList<>(groups.size());
		try {
			for (Group g : groups) {
				privateGroups.add(privateGroupFactory.parsePrivateGroup(g));
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return privateGroups;
	}

	@Override
	public boolean isOurPrivateGroup(Transaction txn, PrivateGroup g)
			throws DbException {
		LocalAuthor localAuthor = identityManager.getLocalAuthor(txn);
		return localAuthor.getId().equals(g.getCreator().getId());
	}

	@Override
	public Collection<PrivateGroup> getPrivateGroups() throws DbException {
		return db.transactionWithResult(true, this::getPrivateGroups);
	}

	@Override
	public boolean isDissolved(Transaction txn, GroupId g) throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getGroupMetadataAsDictionary(txn, g);
			return meta.getBoolean(GROUP_KEY_DISSOLVED);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public boolean isDissolved(GroupId g) throws DbException {
		return db.transactionWithResult(true, txn -> isDissolved(txn, g));
	}

	@Override
	public String getMessageText(MessageId m) throws DbException {
		try {
			return getMessageText(clientHelper.getMessageAsList(m));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public String getMessageText(Transaction txn, MessageId m)
			throws DbException {
		try {
			return getMessageText(clientHelper.getMessageAsList(txn, m));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private String getMessageText(BdfList body) throws FormatException {
		// Message type (0), member (1), parent ID (2), previous message ID (3),
		// text (4), signature (5). Or, for posts with attachments: text (4)
		// may be null, attachment headers (5), signature (6)
		String text = body.getOptionalString(4);
		return text == null ? "" : text;
	}

	@Override
	public Collection<GroupMessageHeader> getHeaders(GroupId g)
			throws DbException {
		return db.transactionWithResult(true, txn -> getHeaders(txn, g));
	}

	@Override
	public List<GroupMessageHeader> getHeaders(Transaction txn, GroupId g)
			throws DbException {
		List<GroupMessageHeader> headers = new ArrayList<>();
		try {
			Map<MessageId, BdfDictionary> allMetadata =
					clientHelper.getMessageMetadataAsDictionary(txn, g);
			// attachments, file manifests and file chunks aren't messages
			// in their own right, skip them
			Map<MessageId, BdfDictionary> metadata = new HashMap<>();
			for (Entry<MessageId, BdfDictionary> e : allMetadata.entrySet()) {
				int type = e.getValue().getInt(KEY_TYPE);
				if (type == JOIN.getInt() || type == POST.getInt())
					metadata.put(e.getKey(), e.getValue());
			}
			// get all authors we need to get the information for
			Set<AuthorId> authors = new HashSet<>();
			for (BdfDictionary meta : metadata.values()) {
				authors.add(getAuthor(meta).getId());
			}
			// get information for all authors
			Map<AuthorId, AuthorInfo> authorInfos = new HashMap<>();
			for (AuthorId id : authors) {
				authorInfos.put(id, authorManager.getAuthorInfo(txn, id));
			}
			// parse the metadata
			for (Entry<MessageId, BdfDictionary> entry : metadata.entrySet()) {
				BdfDictionary meta = entry.getValue();
				if (meta.getInt(KEY_TYPE) == JOIN.getInt()) {
					headers.add(getJoinMessageHeader(txn, g, entry.getKey(),
							meta, authorInfos));
				} else {
					headers.add(getGroupMessageHeader(txn, g, entry.getKey(),
							meta, authorInfos));
				}
			}
			return headers;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private GroupMessageHeader getGroupMessageHeader(Transaction txn, GroupId g,
			MessageId id, BdfDictionary meta,
			Map<AuthorId, AuthorInfo> authorInfos)
			throws DbException, FormatException {

		MessageId parentId = null;
		if (meta.containsKey(KEY_PARENT_MSG_ID)) {
			parentId = new MessageId(meta.getRaw(KEY_PARENT_MSG_ID));
		}
		long timestamp = meta.getLong(KEY_TIMESTAMP);

		Author member = getAuthor(meta);
		AuthorInfo authorInfo;
		if (authorInfos.containsKey(member.getId())) {
			authorInfo = authorInfos.get(member.getId());
		} else {
			authorInfo = authorManager.getAuthorInfo(txn, member.getId());
		}
		boolean read = meta.getBoolean(KEY_READ);
		// Posts without attachments don't store these keys
		boolean hasText = meta.getBoolean(KEY_HAS_TEXT, true);
		List<AttachmentHeader> attachments = parseAttachmentHeaders(g, meta);
		List<GroupFileHeader> files = parseFileHeaders(g, meta);

		return new GroupMessageHeader(g, id, parentId, timestamp, member,
				authorInfo, read, hasText, attachments, files);
	}

	private JoinMessageHeader getJoinMessageHeader(Transaction txn, GroupId g,
			MessageId id, BdfDictionary meta,
			Map<AuthorId, AuthorInfo> authorInfos)
			throws DbException, FormatException {

		GroupMessageHeader header =
				getGroupMessageHeader(txn, g, id, meta, authorInfos);
		boolean creator = meta.getBoolean(KEY_INITIAL_JOIN_MSG);
		return new JoinMessageHeader(header, creator);
	}

	@Override
	public Collection<GroupMember> getMembers(GroupId g) throws DbException {
		return db.transactionWithResult(true, txn -> getMembers(txn, g));
	}

	@Override
	public Collection<GroupMember> getMembers(Transaction txn, GroupId g)
			throws DbException {
		Collection<GroupMember> members = new ArrayList<>();
		Map<Author, Visibility> authors = getMemberAuthors(txn, g);
		LocalAuthor la = identityManager.getLocalAuthor(txn);
		PrivateGroup privateGroup = getPrivateGroup(txn, g);
		for (Entry<Author, Visibility> m : authors.entrySet()) {
			Author a = m.getKey();
			AuthorInfo authorInfo = authorManager.getAuthorInfo(txn, a.getId());
			Status status = authorInfo.getStatus();
			Visibility v = m.getValue();
			ContactId c = null;
			if (v != INVISIBLE &&
					(status == VERIFIED || status == UNVERIFIED)) {
				c = contactManager.getContact(txn, a.getId(), la.getId())
						.getId();
			}
			boolean isCreator = privateGroup.getCreator().equals(a);
			members.add(new GroupMember(a, authorInfo, isCreator, c, v));
		}
		return members;
	}

	private Map<Author, Visibility> getMemberAuthors(Transaction txn, GroupId g)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getGroupMetadataAsDictionary(txn, g);
			BdfList list = meta.getList(GROUP_KEY_MEMBERS);
			Map<Author, Visibility> members = new HashMap<>(list.size());
			for (int i = 0; i < list.size(); i++) {
				BdfDictionary d = list.getDictionary(i);
				Author member = getAuthor(d);
				Visibility v = getVisibility(d);
				members.put(member, v);
			}
			return members;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public boolean isMember(Transaction txn, GroupId g, Author a)
			throws DbException {
		for (Author member : getMemberAuthors(txn, g).keySet()) {
			if (member.equals(a)) return true;
		}
		return false;
	}

	@Override
	public GroupCount getGroupCount(Transaction txn, GroupId g)
			throws DbException {
		return messageTracker.getGroupCount(txn, g);
	}

	@Override
	public GroupCount getGroupCount(GroupId g) throws DbException {
		return messageTracker.getGroupCount(g);
	}

	@Override
	public void setReadFlag(Transaction txn, GroupId g, MessageId m,
			boolean read) throws DbException {
		messageTracker.setReadFlag(txn, g, m, read);
	}

	@Override
	public void setReadFlag(GroupId g, MessageId m, boolean read)
			throws DbException {
		db.transaction(false, txn -> setReadFlag(txn, g, m, read));
	}

	@Override
	public void relationshipRevealed(Transaction txn, GroupId g, AuthorId a,
			boolean byContact) throws FormatException, DbException {
		BdfDictionary meta = clientHelper.getGroupMetadataAsDictionary(txn, g);
		BdfList members = meta.getList(GROUP_KEY_MEMBERS);
		Visibility v = INVISIBLE;
		boolean foundMember = false, changed = false;
		for (int i = 0; i < members.size(); i++) {
			BdfDictionary d = members.getDictionary(i);
			if (a.equals(getAuthor(d).getId())) {
				foundMember = true;
				// Don't update the visibility if the contact is already visible
				if (getVisibility(d) == INVISIBLE) {
					changed = true;
					v = byContact ? REVEALED_BY_CONTACT : REVEALED_BY_US;
					d.put(GROUP_KEY_VISIBILITY, v.getInt());
				}
				break;
			}
		}
		if (!foundMember) throw new ProtocolStateException();
		if (changed) {
			clientHelper.mergeGroupMetadata(txn, g, meta);
			LocalAuthor la = identityManager.getLocalAuthor(txn);
			ContactId c = contactManager.getContact(txn, a, la.getId()).getId();
			Event e = new ContactRelationshipRevealedEvent(g, a, c, v);
			txn.attach(e);
		}
	}

	@Override
	public void registerPrivateGroupHook(PrivateGroupHook hook) {
		hooks.add(hook);
	}

	@Override
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		// An attachment's body is a BDF list followed by raw bytes, so it
		// can't be parsed as a list by the superclass. Handle it here.
		try {
			BdfDictionary metaDict = metadataParser.parse(meta);
			int type = metaDict.getInt(KEY_TYPE);
			if (type == ATTACHMENT.getInt()) {
				handleAttachment(txn, m);
				return ACCEPT_SHARE;
			} else if (type == FILE_CHUNK.getInt()) {
				handleFileChunk(txn, m);
				return ACCEPT_SHARE;
			}
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
		return super.incomingMessage(txn, m, meta);
	}

	@Override
	protected DeliveryAction incomingMessage(Transaction txn, Message m,
			BdfList body, BdfDictionary meta)
			throws DbException, FormatException {

		MessageType type = MessageType.valueOf(meta.getInt(KEY_TYPE));
		switch (type) {
			case JOIN:
				handleJoinMessage(txn, m, meta);
				return ACCEPT_SHARE;
			case POST:
				handleGroupMessage(txn, m, meta);
				return ACCEPT_SHARE;
			case FILE_MANIFEST:
				handleFileManifest(txn, m, meta);
				return ACCEPT_SHARE;
			default:
				// the validator should only let valid types pass
				throw new RuntimeException("Unknown MessageType");
		}
	}

	private void handleAttachment(Transaction txn, Message m)
			throws DbException, FormatException {
		GroupId g = m.getGroupId();
		txn.attach(new GroupAttachmentReceivedEvent(g, m.getId()));
		// If no post that references this attachment has been delivered,
		// start the cleanup timer. It will be stopped when a post that
		// references the attachment is delivered.
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(KEY_TYPE, POST.getInt()));
		Map<MessageId, BdfDictionary> results =
				clientHelper.getMessageMetadataAsDictionary(txn, g, query);
		for (BdfDictionary meta : results.values()) {
			for (AttachmentHeader h : parseAttachmentHeaders(g, meta)) {
				if (h.getMessageId().equals(m.getId())) return;
			}
		}
		// No posts reference this attachment - start the timer
		db.setCleanupTimerDuration(txn, m.getId(),
				MISSING_ATTACHMENT_CLEANUP_DURATION_MS);
		db.startCleanupTimer(txn, m.getId());
	}

	private void handleFileManifest(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		GroupId g = m.getGroupId();
		List<MessageId> chunkIds = parseChunkIds(meta);
		// Chunks that arrived before the manifest were orphans with cleanup
		// timers running. They're referenced now, so stop their timers.
		Set<MessageId> present = new HashSet<>(getChunkIdsInGroup(txn, g));
		for (MessageId id : chunkIds) {
			if (present.contains(id)) db.stopCleanupTimer(txn, id);
		}
		// If no post references this manifest yet, it's an orphan itself
		if (!isManifestReferenced(txn, g, m.getId())) {
			db.setCleanupTimerDuration(txn, m.getId(),
					MISSING_ATTACHMENT_CLEANUP_DURATION_MS);
			db.startCleanupTimer(txn, m.getId());
		}
		reportFileProgress(txn, g, m.getId(), meta, present, null);
	}

	private void handleFileChunk(Transaction txn, Message m)
			throws DbException, FormatException {
		GroupId g = m.getGroupId();
		// Find the manifests, if any, that list this chunk
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(KEY_TYPE, FILE_MANIFEST.getInt()));
		Map<MessageId, BdfDictionary> manifests =
				clientHelper.getMessageMetadataAsDictionary(txn, g, query);
		boolean referenced = false;
		Set<MessageId> present = null;
		for (Entry<MessageId, BdfDictionary> e : manifests.entrySet()) {
			if (!parseChunkIds(e.getValue()).contains(m.getId())) continue;
			referenced = true;
			if (present == null) {
				present = new HashSet<>(getChunkIdsInGroup(txn, g));
				// This chunk isn't marked as delivered until this hook
				// returns, so the query above doesn't include it
				present.add(m.getId());
			}
			reportFileProgress(txn, g, e.getKey(), e.getValue(), present, m);
		}
		// If no manifest lists this chunk yet, it may arrive later. Keep the
		// chunk for a while, then give up on it.
		if (!referenced) {
			db.setCleanupTimerDuration(txn, m.getId(),
					MISSING_ATTACHMENT_CLEANUP_DURATION_MS);
			db.startCleanupTimer(txn, m.getId());
		}
	}

	private boolean isManifestReferenced(Transaction txn, GroupId g,
			MessageId manifestId) throws DbException, FormatException {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(KEY_TYPE, POST.getInt()));
		Map<MessageId, BdfDictionary> posts =
				clientHelper.getMessageMetadataAsDictionary(txn, g, query);
		for (BdfDictionary meta : posts.values()) {
			if (getReferencedIds(meta).contains(manifestId)) return true;
		}
		return false;
	}

	/**
	 * Broadcasts the progress of a file and, once all its chunks are here,
	 * checks that they add up to the declared size.
	 */
	private void reportFileProgress(Transaction txn, GroupId g,
			MessageId manifestId, BdfDictionary manifestMeta,
			Set<MessageId> presentChunks, @Nullable Message arriving)
			throws DbException, FormatException {
		List<MessageId> chunkIds = parseChunkIds(manifestMeta);
		int received = 0;
		for (MessageId id : chunkIds) if (presentChunks.contains(id)) received++;
		if (received == chunkIds.size() &&
				!manifestMeta.containsKey(KEY_FILE_VALID)) {
			boolean valid = chunksAddUpToSize(txn, chunkIds,
					manifestMeta.getLong(KEY_FILE_SIZE), arriving);
			BdfDictionary update = BdfDictionary.of(
					new BdfEntry(KEY_FILE_VALID, valid));
			clientHelper.mergeMessageMetadata(txn, manifestId, update);
			if (!valid) received--; // Unusable, see getFileStatus
		}
		txn.attach(new GroupFileProgressEvent(g, manifestId, received,
				chunkIds.size()));
	}

	/**
	 * Returns true if the payloads of the given chunks add up to the
	 * expected file size. The chunk that is currently being delivered, if
	 * any, may not have its metadata stored yet, so its descriptor length is
	 * read from its body.
	 */
	private boolean chunksAddUpToSize(Transaction txn, List<MessageId> chunkIds,
			long expected, @Nullable Message arriving)
			throws DbException, FormatException {
		long actual = 0;
		for (MessageId id : chunkIds) {
			Message chunk = arriving != null && arriving.getId().equals(id)
					? arriving : db.getMessage(txn, id);
			int offset;
			if (chunk == arriving) {
				offset = clientHelper.toByteArray(
						BdfList.of(FILE_CHUNK.getInt())).length;
			} else {
				BdfDictionary chunkMeta =
						clientHelper.getMessageMetadataAsDictionary(txn, id);
				offset = chunkMeta.getInt(MSG_KEY_DESCRIPTOR_LENGTH);
			}
			actual += chunk.getBody().length - offset;
		}
		return actual == expected;
	}

	private void stopAttachmentCleanupTimers(Transaction txn, Message m,
			List<AttachmentHeader> headers)
			throws DbException, FormatException {
		// Fetch the IDs of all attachments in the group
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(KEY_TYPE, ATTACHMENT.getInt()));
		Collection<MessageId> results =
				clientHelper.getMessageIds(txn, m.getGroupId(), query);
		// Stop the cleanup timers of any attachments that have already
		// been delivered
		for (AttachmentHeader h : headers) {
			MessageId id = h.getMessageId();
			if (results.contains(id)) db.stopCleanupTimer(txn, id);
		}
	}

	private void handleJoinMessage(Transaction txn, Message m,
			BdfDictionary meta) throws FormatException, DbException {
		// find out if contact relationship is visible and then add new member
		Author member = getAuthor(meta);
		BdfDictionary groupMeta = clientHelper
				.getGroupMetadataAsDictionary(txn, m.getGroupId());
		boolean ourGroup = groupMeta.getBoolean(GROUP_KEY_OUR_GROUP);
		Visibility v = VISIBLE;
		if (!ourGroup) {
			AuthorId creatorId = new AuthorId(
					groupMeta.getRaw(GROUP_KEY_CREATOR_ID));
			if (!creatorId.equals(member.getId()))
				v = INVISIBLE;
		}
		addMember(txn, m.getGroupId(), member, v);
		// track message and broadcast event
		messageTracker.trackIncomingMessage(txn, m);
		attachJoinMessageAddedEvent(txn, m, meta, false);
	}

	private void handleGroupMessage(Transaction txn, Message m,
			BdfDictionary meta) throws FormatException, DbException {
		// timestamp must be greater than the timestamps of parent post
		long timestamp = meta.getLong(KEY_TIMESTAMP);
		byte[] parentIdBytes = meta.getOptionalRaw(KEY_PARENT_MSG_ID);
		if (parentIdBytes != null) {
			MessageId parentId = new MessageId(parentIdBytes);
			BdfDictionary parentMeta = clientHelper
					.getMessageMetadataAsDictionary(txn, parentId);
			if (timestamp <= parentMeta.getLong(KEY_TIMESTAMP))
				throw new FormatException();
			MessageType parentType =
					MessageType.valueOf(parentMeta.getInt(KEY_TYPE));
			if (parentType != POST)
				throw new FormatException();
		}
		// and the member's previous message
		byte[] previousMsgIdBytes = meta.getRaw(KEY_PREVIOUS_MSG_ID);
		MessageId previousMsgId = new MessageId(previousMsgIdBytes);
		BdfDictionary previousMeta = clientHelper
				.getMessageMetadataAsDictionary(txn, previousMsgId);
		if (timestamp <= previousMeta.getLong(KEY_TIMESTAMP))
			throw new FormatException();
		// previous message must be from same member
		if (!getAuthor(meta).equals(getAuthor(previousMeta)))
			throw new FormatException();
		// previous message must be a POST or JOIN
		MessageType previousType =
				MessageType.valueOf(previousMeta.getInt(KEY_TYPE));
		if (previousType != JOIN && previousType != POST)
			throw new FormatException();
		// the post's attachments, if any, are no longer orphans
		List<AttachmentHeader> attachments =
				parseAttachmentHeaders(m.getGroupId(), meta);
		if (!attachments.isEmpty())
			stopAttachmentCleanupTimers(txn, m, attachments);
		// nor are any file manifests it references, if they've arrived
		Set<MessageId> referenced = getReferencedIds(meta);
		if (!referenced.isEmpty()) {
			BdfDictionary query = BdfDictionary.of(
					new BdfEntry(KEY_TYPE, FILE_MANIFEST.getInt()));
			Collection<MessageId> manifests =
					clientHelper.getMessageIds(txn, m.getGroupId(), query);
			for (MessageId id : referenced) {
				if (manifests.contains(id)) db.stopCleanupTimer(txn, id);
			}
		}
		// track message and broadcast event
		messageTracker.trackIncomingMessage(txn, m);
		attachGroupMessageAddedEvent(txn, m, meta, false);
	}

	private void attachGroupMessageAddedEvent(Transaction txn, Message m,
			BdfDictionary meta, boolean local)
			throws DbException, FormatException {
		GroupMessageHeader header = getGroupMessageHeader(txn, m.getGroupId(),
				m.getId(), meta, Collections.emptyMap());
		String text = getMessageText(clientHelper.toList(m));
		txn.attach(new GroupMessageAddedEvent(m.getGroupId(), header, text,
				local));
	}

	private void attachJoinMessageAddedEvent(Transaction txn, Message m,
			BdfDictionary meta, boolean local)
			throws DbException, FormatException {
		JoinMessageHeader header = getJoinMessageHeader(txn, m.getGroupId(),
				m.getId(), meta, Collections.emptyMap());
		txn.attach(new GroupMessageAddedEvent(m.getGroupId(), header, "",
				local));
	}

	private void addMember(Transaction txn, GroupId g, Author a, Visibility v)
			throws DbException, FormatException {

		BdfDictionary meta = clientHelper.getGroupMetadataAsDictionary(txn, g);
		BdfList members = meta.getList(GROUP_KEY_MEMBERS);
		members.add(BdfDictionary.of(
				new BdfEntry(KEY_MEMBER, clientHelper.toList(a)),
				new BdfEntry(GROUP_KEY_VISIBILITY, v.getInt())
		));
		clientHelper.mergeGroupMetadata(txn, g, meta);
		for (PrivateGroupHook hook : hooks) {
			hook.addingMember(txn, g, a);
		}
	}

	private Author getAuthor(BdfDictionary meta) throws FormatException {
		return clientHelper.parseAndValidateAuthor(meta.getList(KEY_MEMBER));
	}

	private Visibility getVisibility(BdfDictionary meta)
			throws FormatException {
		return Visibility.valueOf(meta.getInt(GROUP_KEY_VISIBILITY));
	}

}
