package org.briarproject.briar.attachment;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.attachment.event.FileProgressEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.util.StringUtils.utf8IsTooLong;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.attachment.MediaConstants.FILE_CHUNK_PAYLOAD_LENGTH;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_CHUNKS;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_NAME_LENGTH;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;

/**
 * Stores and reads files that are too large to fit into a single sync
 * message, by splitting them into chunks that are ordinary messages of the
 * client that owns them, plus a manifest listing the chunks in order.
 * <p>
 * Because the chunks are ordinary messages, they are transferred and (in a
 * private group) forwarded over any transport, so a file can arrive piece by
 * piece across many short encounters instead of needing one long connection.
 * <p>
 * Neither manifests nor chunks are signed. They are authenticated by
 * content-addressing: a signed or otherwise authenticated message of the
 * owning client references the manifest by message ID, the manifest lists
 * the chunk IDs, and every message ID is a hash of the message. The bytes a
 * reader assembles are therefore exactly the bytes the sender committed to.
 * <p>
 * The client this store belongs to supplies the message types, metadata and
 * queries that identify its own manifests and chunks, so that two clients
 * can each keep their own files without seeing each other's.
 */
@Immutable
@NotNullByDefault
public class ChunkedFileStore {

	/**
	 * Metadata key for a manifest's file name.
	 */
	public static final String KEY_FILE_NAME = "fileName";

	/**
	 * Metadata key for a manifest's content type.
	 */
	public static final String KEY_FILE_CONTENT_TYPE = "fileContentType";

	/**
	 * Metadata key for a manifest's declared file size in bytes.
	 */
	public static final String KEY_FILE_SIZE = "fileSize";

	/**
	 * Metadata key for the list of a manifest's chunk IDs, in order.
	 */
	public static final String KEY_FILE_CHUNK_IDS = "fileChunkIds";

	/**
	 * Metadata key set on a manifest once all its chunks have arrived: true
	 * if the chunks add up to the declared file size, false if the sender
	 * lied about the size and the file must not be read.
	 */
	public static final String KEY_FILE_VALID = "fileValid";

	/**
	 * The parts of file storage that differ between the clients that use it.
	 */
	public interface Client {

		/**
		 * Returns the client's message type for a manifest, which is the
		 * first element of a manifest's body.
		 */
		int getManifestType();

		/**
		 * Returns the client's message type for a chunk, which is the first
		 * element of a chunk's descriptor.
		 */
		int getChunkType();

		/**
		 * Returns the metadata identifying a locally created manifest or
		 * chunk of the given type, to which this store adds its own keys.
		 */
		BdfDictionary getLocalFileMetadata(int messageType, long timestamp);

		/**
		 * Returns a metadata query matching the client's manifests.
		 */
		BdfDictionary getManifestQuery();

		/**
		 * Returns a metadata query matching the client's chunks.
		 */
		BdfDictionary getChunkQuery();

		/**
		 * Returns true if a delivered message of this client references the
		 * given manifest, so the file is wanted and must not be cleaned up.
		 */
		boolean isManifestReferenced(Transaction txn, GroupId g,
				MessageId manifestId) throws DbException, FormatException;

		/**
		 * Returns how long to keep a manifest or chunk that no delivered
		 * message references, before deleting it.
		 */
		long getMissingFileCleanupDurationMs();
	}

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final Client client;

	public ChunkedFileStore(DatabaseComponent db, ClientHelper clientHelper,
			Client client) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.client = client;
	}

	// Validation. Both clients' validators call these, so the rules that
	// protect readers from a malformed or lying manifest live in one place.

	/**
	 * Checks a manifest's body and returns the metadata describing the file.
	 * The caller adds its own metadata, such as the message type.
	 */
	public static BdfDictionary validateManifest(BdfList body)
			throws FormatException {
		// Message type, file name, content type, size, chunk IDs
		checkSize(body, 5);
		String name = body.getString(1);
		checkLength(name, 1, MAX_FILE_NAME_LENGTH);
		String contentType = body.getString(2);
		checkLength(contentType, 1, MAX_CONTENT_TYPE_BYTES);
		long size = body.getLong(3);
		if (size < 1 || size > MAX_FILE_SIZE) throw new FormatException();
		// Every chunk except the last carries FILE_CHUNK_PAYLOAD_LENGTH
		// bytes, so the number of chunks follows from the size
		int expectedChunks = (int) ((size + FILE_CHUNK_PAYLOAD_LENGTH - 1)
				/ FILE_CHUNK_PAYLOAD_LENGTH);
		BdfList chunkIds = body.getList(4);
		if (chunkIds.size() != expectedChunks) throw new FormatException();
		Set<MessageId> unique = new HashSet<>();
		for (int i = 0; i < chunkIds.size(); i++) {
			byte[] id = chunkIds.getRaw(i);
			checkLength(id, UniqueId.LENGTH);
			// A chunk can't appear twice in the same file
			if (!unique.add(new MessageId(id))) throw new FormatException();
		}
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_FILE_NAME, name);
		meta.put(KEY_FILE_CONTENT_TYPE, contentType);
		// Also stored under the generic key so that a chunked image can be
		// read through the attachment reader like a single-message attachment
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(KEY_FILE_SIZE, size);
		meta.put(KEY_FILE_CHUNK_IDS, chunkIds);
		return meta;
	}

	/**
	 * Checks a chunk's descriptor and payload length.
	 */
	public static void validateChunk(BdfList descriptor, long payloadLength)
			throws FormatException {
		// Message type, chunk index, followed by the chunk's bytes. The
		// index makes chunks with identical content distinct messages.
		checkSize(descriptor, 2);
		int index = descriptor.getInt(1);
		if (index < 0 || index >= MAX_FILE_CHUNKS) throw new FormatException();
		if (payloadLength < 1 || payloadLength > FILE_CHUNK_PAYLOAD_LENGTH)
			throw new FormatException();
	}

	// Storing a local file

	/**
	 * Splits the given file into chunks and stores them, plus a manifest
	 * listing them, as temporary unshared messages in the given group. The
	 * file is not sent until {@link #shareFile(Transaction, MessageId)} is
	 * called for the manifest.
	 *
	 * @throws FileTooBigException If the file is larger than
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#MAX_FILE_SIZE}
	 */
	public FileHeader addLocalFile(GroupId groupId, long timestamp,
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
		// so it's sent first. All chunks share the next timestamp, so the
		// sync layer sends them in a random order: contacts who each receive
		// part of the file then hold different pieces and can complete each
		// other's copy, rather than all holding the same prefix.
		List<MessageId> chunkIds = new ArrayList<>();
		long size = 0;
		try {
			byte[] buf = new byte[FILE_CHUNK_PAYLOAD_LENGTH];
			while (true) {
				int read = readFully(in, buf);
				if (read <= 0) break;
				size += read;
				if (size > MAX_FILE_SIZE) throw new FileTooBigException();
				// The descriptor includes the chunk's index, so chunks with
				// identical content at different positions in the file (or
				// in different files) still have distinct message IDs
				byte[] descriptor = clientHelper.toByteArray(
						BdfList.of(client.getChunkType(), chunkIds.size()));
				byte[] body = new byte[descriptor.length + read];
				System.arraycopy(descriptor, 0, body, 0, descriptor.length);
				System.arraycopy(buf, 0, body, descriptor.length, read);
				long chunkTimestamp = timestamp + 1;
				Message m = clientHelper.createMessage(groupId, chunkTimestamp,
						body);
				BdfDictionary meta = client.getLocalFileMetadata(
						client.getChunkType(), chunkTimestamp);
				meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptor.length);
				db.transaction(false, txn ->
						clientHelper.addLocalMessage(txn, m, meta, false,
								true));
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
		BdfList body = BdfList.of(client.getManifestType(), name, contentType,
				size, chunkIdList);
		Message manifest = clientHelper.createMessage(groupId, timestamp, body);
		BdfDictionary meta = client.getLocalFileMetadata(
				client.getManifestType(), timestamp);
		meta.put(KEY_FILE_NAME, name);
		meta.put(KEY_FILE_CONTENT_TYPE, contentType);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(KEY_FILE_SIZE, size);
		meta.put(KEY_FILE_CHUNK_IDS, chunkIdList);
		meta.put(KEY_FILE_VALID, true); // We created the chunks ourselves
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, manifest, meta, false, true));
		return new FileHeader(groupId, manifest.getId(), name, contentType,
				size);
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

	/**
	 * Removes an unsent file and its chunks.
	 */
	public void removeFile(FileHeader header) throws DbException {
		db.transaction(false, txn -> {
			for (MessageId id : getChunkIds(txn, header.getManifestId())) {
				db.removeMessage(txn, id);
			}
			db.removeMessage(txn, header.getManifestId());
		});
	}

	/**
	 * Deletes a received file and its chunks, for example when the message
	 * that shared it is deleted or auto-deleted. Chunks that haven't
	 * arrived, and a manifest that hasn't arrived, are ignored.
	 */
	public void deleteFile(Transaction txn, MessageId manifestId)
			throws DbException {
		List<MessageId> chunkIds;
		try {
			chunkIds = getChunkIds(txn, manifestId);
		} catch (DbException e) {
			// The manifest hasn't arrived, so we don't know its chunks.
			// Any chunks that have arrived will be cleaned up as orphans.
			chunkIds = new ArrayList<>();
		}
		for (MessageId id : chunkIds) deleteIfPresent(txn, id);
		deleteIfPresent(txn, manifestId);
	}

	private void deleteIfPresent(Transaction txn, MessageId id)
			throws DbException {
		try {
			db.deleteMessage(txn, id);
			db.deleteMessageMetadata(txn, id);
		} catch (NoSuchMessageException e) {
			// Continue
		}
	}

	/**
	 * Marks a locally created file's manifest and chunks as shared and
	 * permanent, so they are sent to contacts and not cleaned up. Called
	 * when the message that references the file is added.
	 */
	public void shareFile(Transaction txn, MessageId manifestId)
			throws DbException {
		db.setMessageShared(txn, manifestId);
		db.setMessagePermanent(txn, manifestId);
		for (MessageId chunkId : getChunkIds(txn, manifestId)) {
			db.setMessageShared(txn, chunkId);
			db.setMessagePermanent(txn, chunkId);
		}
	}

	/**
	 * Returns true if the message with the given ID is one of this client's
	 * file manifests.
	 */
	public boolean isManifest(Transaction txn, MessageId m)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, m);
			return meta.containsKey(KEY_FILE_CHUNK_IDS);
		} catch (NoSuchMessageException e) {
			return false;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	/**
	 * Returns the IDs of the given manifest's chunks, in order.
	 */
	public List<MessageId> getChunkIds(Transaction txn, MessageId manifestId)
			throws DbException {
		try {
			BdfDictionary meta = clientHelper
					.getMessageMetadataAsDictionary(txn, manifestId);
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

	// Reading a file

	/**
	 * Returns the header of the file whose manifest has the given message
	 * ID.
	 *
	 * @throws NoSuchMessageException If the message is not a file manifest
	 * in the given group, or has not arrived
	 */
	public FileHeader getFileHeader(Transaction txn, GroupId groupId,
			MessageId manifestId) throws DbException {
		try {
			Message m = db.getMessage(txn, manifestId);
			// Don't let a manifest be read in the context of another group
			if (!m.getGroupId().equals(groupId))
				throw new NoSuchMessageException();
			BdfDictionary meta = clientHelper
					.getMessageMetadataAsDictionary(txn, manifestId);
			if (!meta.containsKey(KEY_FILE_CHUNK_IDS))
				throw new NoSuchMessageException();
			return new FileHeader(groupId, manifestId,
					meta.getString(KEY_FILE_NAME),
					meta.getString(KEY_FILE_CONTENT_TYPE),
					meta.getLong(KEY_FILE_SIZE));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	public FileHeader getFileHeader(GroupId groupId, MessageId manifestId)
			throws DbException {
		return db.transactionWithResult(true,
				txn -> getFileHeader(txn, groupId, manifestId));
	}

	/**
	 * Returns how much of the given file has been received.
	 */
	public FileStatus getFileStatus(Transaction txn, FileHeader header)
			throws DbException {
		try {
			BdfDictionary manifestMeta;
			try {
				manifestMeta = clientHelper.getMessageMetadataAsDictionary(txn,
						header.getManifestId());
			} catch (NoSuchMessageException e) {
				// Chunks may have arrived, but without the manifest we can't
				// tell which of them belong to this file
				return new FileStatus(header, false, 0);
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
							manifestMeta.getLong(KEY_FILE_SIZE), null, 0);
				}
				if (!valid) received--;
			}
			return new FileStatus(header, true, received);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	public FileStatus getFileStatus(FileHeader header) throws DbException {
		return db.transactionWithResult(true,
				txn -> getFileStatus(txn, header));
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

	private Collection<MessageId> getChunkIdsInGroup(Transaction txn, GroupId g)
			throws DbException {
		try {
			return clientHelper.getMessageIds(txn, g, client.getChunkQuery());
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	/**
	 * Returns true if the payloads of the given chunks add up to the
	 * expected file size. The chunk that is currently being delivered, if
	 * any, may not have its metadata stored yet, so its descriptor length is
	 * passed in from the metadata the validator produced for it.
	 */
	private boolean chunksAddUpToSize(Transaction txn, List<MessageId> chunkIds,
			long expected, @Nullable Message arriving,
			int arrivingDescriptorLength) throws DbException, FormatException {
		long actual = 0;
		for (MessageId id : chunkIds) {
			Message chunk = arriving != null && arriving.getId().equals(id)
					? arriving : db.getMessage(txn, id);
			int offset;
			if (chunk == arriving) {
				offset = arrivingDescriptorLength;
			} else {
				BdfDictionary chunkMeta =
						clientHelper.getMessageMetadataAsDictionary(txn, id);
				offset = chunkMeta.getInt(MSG_KEY_DESCRIPTOR_LENGTH);
			}
			actual += chunk.getBody().length - offset;
		}
		return actual == expected;
	}

	/**
	 * Returns a stream for reading the given file, which must have been
	 * fully received. The stream reads the file's chunks from the database
	 * one at a time, in transactions of its own, so it may be used after the
	 * given transaction has ended and the whole file is never held in
	 * memory.
	 *
	 * @throws NoSuchMessageException If the file has not been fully received
	 */
	public InputStream getFile(Transaction txn, FileHeader header)
			throws DbException {
		// Check that the file is complete and valid before handing out a
		// stream, so callers don't get a truncated file
		FileStatus status = getFileStatus(txn, header);
		if (!status.isComplete()) throw new NoSuchMessageException();
		List<MessageId> chunkIds = getChunkIds(txn, header.getManifestId());
		return new ChunkInputStream(header.getGroupId(), chunkIds);
	}

	public InputStream getFile(FileHeader header) throws DbException {
		return db.transactionWithResult(true, txn -> getFile(txn, header));
	}

	/**
	 * Returns the bytes of the given chunk of a file, which must have been
	 * fully received. Every chunk except the last holds
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#FILE_CHUNK_PAYLOAD_LENGTH}
	 * bytes, so byte offset {@code n} of the file is in chunk
	 * {@code n / payloadLength}. This gives random access for playing audio
	 * and video straight from the database, without writing the decrypted
	 * file to disk.
	 *
	 * @throws NoSuchMessageException If the file has not been fully received
	 * or the index is out of range
	 */
	public byte[] getFileChunk(FileHeader header, int index)
			throws DbException {
		return db.transactionWithResult(true, txn -> {
			FileStatus status = getFileStatus(txn, header);
			if (!status.isComplete()) throw new NoSuchMessageException();
			List<MessageId> chunkIds = getChunkIds(txn, header.getManifestId());
			if (index < 0 || index >= chunkIds.size())
				throw new NoSuchMessageException();
			return loadChunkPayload(txn, header.getGroupId(),
					chunkIds.get(index));
		});
	}

	private byte[] loadChunkPayload(Transaction txn, GroupId groupId,
			MessageId id) throws DbException {
		Message m = db.getMessage(txn, id);
		// Check the chunk belongs to this group, so a manifest can't be used
		// to read messages from other groups
		if (!m.getGroupId().equals(groupId))
			throw new NoSuchMessageException();
		try {
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, id);
			int offset = meta.getInt(MSG_KEY_DESCRIPTOR_LENGTH);
			byte[] body = m.getBody();
			byte[] payload = new byte[body.length - offset];
			System.arraycopy(body, offset, payload, 0, payload.length);
			return payload;
		} catch (FormatException e) {
			throw new DbException(e);
		}
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
				return db.transactionWithResult(true, txn ->
						new ByteArrayInputStream(
								loadChunkPayload(txn, groupId, id)));
			} catch (DbException e) {
				throw new IOException(e);
			}
		}
	}

	// Receiving files. Manifests and chunks may arrive in any order, before
	// or after the message that references the manifest, so each of them
	// keeps a cleanup timer until that message makes the file wanted.

	/**
	 * Handles an incoming manifest. Call from the client's incoming message
	 * hook.
	 */
	public void incomingManifest(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		GroupId g = m.getGroupId();
		List<MessageId> chunkIds = parseChunkIds(meta);
		Set<MessageId> present = new HashSet<>(getChunkIdsInGroup(txn, g));
		boolean referenced = client.isManifestReferenced(txn, g, m.getId());
		if (referenced) {
			// Chunks that arrived before the manifest were orphans with
			// cleanup timers running. The file is wanted, so stop them.
			for (MessageId id : chunkIds) {
				if (present.contains(id)) db.stopCleanupTimer(txn, id);
			}
		} else {
			// Nothing references this file yet. The manifest and the chunks
			// that are already here are all orphans: give them a deadline,
			// which is lifted if the referencing message arrives.
			startCleanupTimer(txn, m.getId());
			for (MessageId id : chunkIds) {
				if (present.contains(id)) startCleanupTimer(txn, id);
			}
		}
		reportProgress(txn, g, m.getId(), meta, present, null, 0);
	}

	/**
	 * Handles an incoming chunk. Call from the client's incoming message
	 * hook.
	 */
	public void incomingChunk(Transaction txn, Message m, BdfDictionary meta)
			throws DbException, FormatException {
		GroupId g = m.getGroupId();
		int descriptorLength = meta.getInt(MSG_KEY_DESCRIPTOR_LENGTH);
		// Find the manifests, if any, that list this chunk
		Map<MessageId, BdfDictionary> manifests = clientHelper
				.getMessageMetadataAsDictionary(txn, g,
						client.getManifestQuery());
		boolean wanted = false;
		Set<MessageId> present = null;
		for (Entry<MessageId, BdfDictionary> e : manifests.entrySet()) {
			if (!parseChunkIds(e.getValue()).contains(m.getId())) continue;
			// This chunk is wanted if any file that lists it is wanted
			if (client.isManifestReferenced(txn, g, e.getKey())) wanted = true;
			if (present == null) {
				present = new HashSet<>(getChunkIdsInGroup(txn, g));
				// This chunk isn't marked as delivered until this hook
				// returns, so the query above doesn't include it
				present.add(m.getId());
			}
			reportProgress(txn, g, e.getKey(), e.getValue(), present, m,
					descriptorLength);
		}
		// If no manifest lists this chunk yet, it may arrive later; and if
		// the only files that list it are themselves unreferenced, the chunk
		// may never be wanted. Keep it for a while, then give up on it.
		if (!wanted) startCleanupTimer(txn, m.getId());
	}

	/**
	 * Stops the cleanup timers of the given manifests and their chunks,
	 * because a message referencing them has been delivered. Call from the
	 * client's incoming message hook when a message that references files is
	 * delivered.
	 */
	public void onFilesReferenced(Transaction txn, GroupId g,
			Collection<MessageId> manifestIds)
			throws DbException, FormatException {
		if (manifestIds.isEmpty()) return;
		Collection<MessageId> presentManifests;
		Collection<MessageId> presentChunks = null;
		try {
			presentManifests = clientHelper.getMessageIds(txn, g,
					client.getManifestQuery());
		} catch (FormatException e) {
			throw new DbException(e);
		}
		for (MessageId manifestId : manifestIds) {
			if (!presentManifests.contains(manifestId)) continue;
			db.stopCleanupTimer(txn, manifestId);
			// The chunks that are already here are wanted now too. A chunk
			// that arrives later finds a referenced manifest and isn't given
			// a timer at all. Chunks that haven't arrived aren't in the
			// database, so their timers can't be touched.
			if (presentChunks == null) {
				presentChunks = getChunkIdsInGroup(txn, g);
			}
			for (MessageId chunkId : getChunkIds(txn, manifestId)) {
				if (presentChunks.contains(chunkId)) {
					db.stopCleanupTimer(txn, chunkId);
				}
			}
		}
	}

	private void startCleanupTimer(Transaction txn, MessageId m)
			throws DbException {
		db.setCleanupTimerDuration(txn, m,
				client.getMissingFileCleanupDurationMs());
		db.startCleanupTimer(txn, m);
	}

	/**
	 * Broadcasts the progress of a file and, once all its chunks are here,
	 * checks that they add up to the declared size.
	 */
	private void reportProgress(Transaction txn, GroupId g,
			MessageId manifestId, BdfDictionary manifestMeta,
			Set<MessageId> presentChunks, @Nullable Message arriving,
			int arrivingDescriptorLength) throws DbException, FormatException {
		List<MessageId> chunkIds = parseChunkIds(manifestMeta);
		int received = 0;
		for (MessageId id : chunkIds) {
			if (presentChunks.contains(id)) received++;
		}
		if (received == chunkIds.size() &&
				!manifestMeta.containsKey(KEY_FILE_VALID)) {
			boolean valid = chunksAddUpToSize(txn, chunkIds,
					manifestMeta.getLong(KEY_FILE_SIZE), arriving,
					arrivingDescriptorLength);
			BdfDictionary update = BdfDictionary.of(
					new BdfEntry(KEY_FILE_VALID, valid));
			clientHelper.mergeMessageMetadata(txn, manifestId, update);
			if (!valid) received--; // Unusable, see getFileStatus
		}
		txn.attach(new FileProgressEvent(g, manifestId, received,
				chunkIds.size()));
	}
}
