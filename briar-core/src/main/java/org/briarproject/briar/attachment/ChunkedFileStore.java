package org.briarproject.briar.attachment;

import org.briarproject.bramble.api.Bytes;
import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.briar.api.attachment.event.FileProgressEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * client that owns them, plus a manifest describing the file.
 * <p>
 * Because the chunks are ordinary messages, they are transferred and (in a
 * private group) forwarded over any transport, so a file can arrive piece by
 * piece across many short encounters instead of needing one long connection.
 * <p>
 * Neither manifests nor chunks are signed. They are authenticated by
 * content-addressing, in two layers that bind each chunk to exactly one
 * file. The manifest lists a hash of every chunk's position and bytes. Each
 * chunk carries the ID of its manifest, and that ID is part of the chunk's
 * own message ID. So a chunk belongs to one manifest and cannot be claimed
 * by another, which is what makes deleting a file safe: only the chunks that
 * name the deleted manifest are removed. A message of the owning client, in
 * turn, references the manifest by its ID, so the bytes a reader assembles
 * are exactly the bytes the sender committed to.
 * <p>
 * A chunk declares its manifest as a dependency, so the sync layer holds the
 * chunk until the manifest has arrived and been delivered. The chunk is then
 * checked against the manifest and rejected if it does not match. A chunk
 * that has not been checked is never forwarded to other devices.
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
	 * Metadata key for the list of a manifest's chunk hashes, in order.
	 * Each hash covers the chunk's index and its bytes.
	 */
	public static final String KEY_FILE_CHUNK_HASHES = "fileChunkHashes";

	/**
	 * Metadata key for the ID of the manifest a chunk belongs to.
	 */
	public static final String KEY_FILE_MANIFEST_ID = "fileManifestId";

	/**
	 * Metadata key for a chunk's index within its file.
	 */
	public static final String KEY_FILE_CHUNK_INDEX = "fileChunkIndex";

	/**
	 * Label for hashing a chunk's index and bytes.
	 */
	private static final String LABEL_CHUNK_HASH =
			"org.briarproject.briar.attachment/CHUNK_HASH";

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
	private final CryptoComponent crypto;
	private final Client client;

	public ChunkedFileStore(DatabaseComponent db, ClientHelper clientHelper,
			CryptoComponent crypto, Client client) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.crypto = crypto;
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
		// Message type, file name, content type, size, chunk hashes
		checkSize(body, 5);
		String name = body.getString(1);
		checkLength(name, 1, MAX_FILE_NAME_LENGTH);
		String contentType = body.getString(2);
		checkLength(contentType, 1, MAX_CONTENT_TYPE_BYTES);
		long size = body.getLong(3);
		if (size < 1 || size > MAX_FILE_SIZE) throw new FormatException();
		// Every chunk except the last carries FILE_CHUNK_PAYLOAD_LENGTH
		// bytes, so the number of chunks follows from the size
		BdfList hashes = body.getList(4);
		if (hashes.size() != getChunkCount(size)) throw new FormatException();
		Set<Bytes> unique = new HashSet<>();
		for (int i = 0; i < hashes.size(); i++) {
			byte[] hash = hashes.getRaw(i);
			checkLength(hash, UniqueId.LENGTH);
			// A hash covers its chunk's index, so the same hash can't
			// legitimately appear at two indices
			if (!unique.add(new Bytes(hash))) throw new FormatException();
		}
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_FILE_NAME, name);
		meta.put(KEY_FILE_CONTENT_TYPE, contentType);
		// Also stored under the generic key so that a chunked image can be
		// read through the attachment reader like a single-message attachment
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(KEY_FILE_SIZE, size);
		meta.put(KEY_FILE_CHUNK_HASHES, hashes);
		return meta;
	}

	/**
	 * Checks a chunk's descriptor and payload length, and returns the
	 * metadata naming the chunk's manifest and index. The caller adds its
	 * own metadata and declares the manifest as a dependency, so that the
	 * chunk is not delivered until the manifest is, and can then be checked
	 * against it.
	 */
	public static BdfDictionary validateChunk(BdfList descriptor,
			long payloadLength) throws FormatException {
		// Message type, manifest ID, chunk index, followed by the chunk's
		// bytes. The manifest ID binds the chunk to one file; the index
		// makes chunks with identical content distinct messages.
		checkSize(descriptor, 3);
		byte[] manifestId = descriptor.getRaw(1);
		checkLength(manifestId, UniqueId.LENGTH);
		int index = descriptor.getInt(2);
		if (index < 0 || index >= MAX_FILE_CHUNKS) throw new FormatException();
		// The exact length expected at this index is known only from the
		// manifest, and is checked when the chunk is delivered
		if (payloadLength < 1 || payloadLength > FILE_CHUNK_PAYLOAD_LENGTH)
			throw new FormatException();
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_FILE_MANIFEST_ID, manifestId);
		meta.put(KEY_FILE_CHUNK_INDEX, index);
		return meta;
	}

	/**
	 * Returns the number of chunks in a file of the given size.
	 */
	public static int getChunkCount(long size) {
		return (int) ((size + FILE_CHUNK_PAYLOAD_LENGTH - 1)
				/ FILE_CHUNK_PAYLOAD_LENGTH);
	}

	/**
	 * Returns the number of bytes carried by the chunk at the given index
	 * of a file of the given size.
	 */
	private static int getPayloadLength(long size, int index) {
		long remaining = size - (long) index * FILE_CHUNK_PAYLOAD_LENGTH;
		return (int) Math.min(remaining, FILE_CHUNK_PAYLOAD_LENGTH);
	}

	private byte[] hashChunk(int index, byte[] payload, int offset, int len) {
		byte[] indexBytes = new byte[] {
				(byte) (index >> 24), (byte) (index >> 16),
				(byte) (index >> 8), (byte) index
		};
		byte[] bytes = payload;
		if (offset != 0 || len != payload.length) {
			bytes = Arrays.copyOfRange(payload, offset, offset + len);
		}
		return crypto.hash(LABEL_CHUNK_HASH, indexBytes, bytes);
	}

	// Storing a local file

	/**
	 * Splits the given file into chunks and stores them, plus a manifest
	 * describing them, as temporary unshared messages in the given group.
	 * The file is not sent until {@link #shareFile(Transaction, GroupId,
	 * MessageId)} is called for the manifest.
	 * <p>
	 * The file is read twice: once to hash the chunks, which the manifest
	 * lists, and again to store the chunks, which carry the manifest's ID.
	 *
	 * @throws FileTooBigException If the file is larger than
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#MAX_FILE_SIZE}
	 */
	public FileHeader addLocalFile(GroupId groupId, long timestamp,
			String name, String contentType, StreamSource source)
			throws DbException, IOException {
		if (name.isEmpty() || utf8IsTooLong(name, MAX_FILE_NAME_LENGTH))
			throw new IllegalArgumentException();
		if (contentType.isEmpty() ||
				utf8IsTooLong(contentType, MAX_CONTENT_TYPE_BYTES)) {
			throw new IllegalArgumentException();
		}
		// First pass: hash the chunks
		BdfList hashes = new BdfList();
		long size = 0;
		try (InputStream in = source.openStream()) {
			byte[] buf = new byte[FILE_CHUNK_PAYLOAD_LENGTH];
			while (true) {
				int read = readFully(in, buf);
				if (read <= 0) break;
				size += read;
				if (size > MAX_FILE_SIZE) throw new FileTooBigException();
				hashes.add(hashChunk(hashes.size(), buf, 0, read));
				if (read < buf.length) break; // Last chunk
			}
		}
		if (size == 0) throw new IllegalArgumentException("Empty file");
		// Store the manifest. It gets the given timestamp so it's sent
		// before the chunks, which is also needed for the chunks to be
		// delivered: they depend on it.
		BdfList body = BdfList.of(client.getManifestType(), name, contentType,
				size, hashes);
		Message manifest = clientHelper.createMessage(groupId, timestamp, body);
		MessageId manifestId = manifest.getId();
		BdfDictionary manifestMeta = client.getLocalFileMetadata(
				client.getManifestType(), timestamp);
		manifestMeta.put(KEY_FILE_NAME, name);
		manifestMeta.put(KEY_FILE_CONTENT_TYPE, contentType);
		manifestMeta.put(MSG_KEY_CONTENT_TYPE, contentType);
		manifestMeta.put(KEY_FILE_SIZE, size);
		manifestMeta.put(KEY_FILE_CHUNK_HASHES, hashes);
		db.transaction(false, txn -> clientHelper.addLocalMessage(txn,
				manifest, manifestMeta, false, true));
		// Second pass: store the chunks. All chunks share the next
		// timestamp, so the sync layer sends them in a random order:
		// contacts who each receive part of the file then hold different
		// pieces and can complete each other's copy, rather than all
		// holding the same prefix.
		long chunkTimestamp = timestamp + 1;
		List<MessageId> chunkIds = new ArrayList<>();
		try (InputStream in = source.openStream()) {
			byte[] buf = new byte[FILE_CHUNK_PAYLOAD_LENGTH];
			int index = 0;
			while (true) {
				int read = readFully(in, buf);
				if (read <= 0) break;
				// The file must not have changed between the two passes
				if (index >= hashes.size() ||
						!Arrays.equals(hashes.getRaw(index),
								hashChunk(index, buf, 0, read))) {
					throw new IOException("File changed while being stored");
				}
				byte[] descriptor = clientHelper.toByteArray(BdfList.of(
						client.getChunkType(), manifestId, index));
				byte[] chunkBody = new byte[descriptor.length + read];
				System.arraycopy(descriptor, 0, chunkBody, 0,
						descriptor.length);
				System.arraycopy(buf, 0, chunkBody, descriptor.length, read);
				Message m = clientHelper.createMessage(groupId, chunkTimestamp,
						chunkBody);
				BdfDictionary meta = client.getLocalFileMetadata(
						client.getChunkType(), chunkTimestamp);
				meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptor.length);
				meta.put(KEY_FILE_MANIFEST_ID, manifestId);
				meta.put(KEY_FILE_CHUNK_INDEX, index);
				db.transaction(false, txn ->
						clientHelper.addLocalMessage(txn, m, meta, false,
								true));
				chunkIds.add(m.getId());
				index++;
				if (read < buf.length) break; // Last chunk
			}
			if (index != hashes.size()) {
				throw new IOException("File changed while being stored");
			}
		} catch (IOException | RuntimeException e) {
			// Don't leave an orphaned manifest or chunks behind
			db.transaction(false, txn -> {
				for (MessageId id : chunkIds) db.removeMessage(txn, id);
				db.removeMessage(txn, manifestId);
			});
			throw e;
		}
		return new FileHeader(groupId, manifestId, name, contentType, size);
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
			for (MessageId id : getChunkIds(txn, header.getGroupId(),
					header.getManifestId())) {
				db.removeMessage(txn, id);
			}
			db.removeMessage(txn, header.getManifestId());
		});
	}

	/**
	 * Deletes a file and its chunks, for example when the message that
	 * shared it is deleted or auto-deleted. Only chunks that name the given
	 * manifest are deleted, so a manifest written by someone else that
	 * happens to describe the same bytes cannot be used to delete this
	 * file. If another delivered message still references the manifest,
	 * nothing is deleted. Chunks that haven't arrived, and a manifest that
	 * hasn't arrived, are ignored.
	 */
	public void deleteFile(Transaction txn, GroupId g, MessageId manifestId)
			throws DbException {
		try {
			if (client.isManifestReferenced(txn, g, manifestId)) return;
		} catch (FormatException e) {
			throw new DbException(e);
		}
		for (MessageId id : getChunkIds(txn, g, manifestId)) {
			deleteIfPresent(txn, id);
		}
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
	public void shareFile(Transaction txn, GroupId g, MessageId manifestId)
			throws DbException {
		db.setMessageShared(txn, manifestId);
		db.setMessagePermanent(txn, manifestId);
		for (MessageId chunkId : getChunkIds(txn, g, manifestId)) {
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
			return meta.containsKey(KEY_FILE_CHUNK_HASHES);
		} catch (NoSuchMessageException e) {
			return false;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	// Looking up chunks. Chunks are found by the manifest they name and
	// their index, which the database indexes, so no query needs to look at
	// chunks of other files.

	private BdfDictionary getChunkQuery(MessageId manifestId) {
		BdfDictionary query = client.getChunkQuery();
		query.put(KEY_FILE_MANIFEST_ID, manifestId);
		return query;
	}

	/**
	 * Returns the IDs of the delivered chunks that name the given manifest.
	 */
	private Collection<MessageId> getChunkIds(Transaction txn, GroupId g,
			MessageId manifestId) throws DbException {
		try {
			return clientHelper.getMessageIds(txn, g,
					getChunkQuery(manifestId));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	/**
	 * Returns the ID of the delivered chunk with the given index that names
	 * the given manifest.
	 *
	 * @throws NoSuchMessageException If the chunk has not arrived
	 */
	private MessageId getChunkId(Transaction txn, GroupId g,
			MessageId manifestId, int index) throws DbException {
		BdfDictionary query = getChunkQuery(manifestId);
		query.put(KEY_FILE_CHUNK_INDEX, index);
		try {
			Collection<MessageId> ids =
					clientHelper.getMessageIds(txn, g, query);
			// Two chunks with the same manifest and index have passed the
			// same hash check, so they hold the same bytes
			if (ids.isEmpty()) throw new NoSuchMessageException();
			return ids.iterator().next();
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	/**
	 * Returns how many distinct chunks of the given file have arrived.
	 */
	private int countChunks(Transaction txn, GroupId g, MessageId manifestId)
			throws DbException, FormatException {
		Set<Integer> indices = new HashSet<>();
		for (BdfDictionary meta : clientHelper.getMessageMetadataAsDictionary(
				txn, g, getChunkQuery(manifestId)).values()) {
			indices.add(meta.getInt(KEY_FILE_CHUNK_INDEX));
		}
		return indices.size();
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
			if (!meta.containsKey(KEY_FILE_CHUNK_HASHES))
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
	 * Returns how much of the given file has been received. Every chunk
	 * counted has been checked against the manifest, so a complete file is
	 * a correct file.
	 */
	public FileStatus getFileStatus(Transaction txn, FileHeader header)
			throws DbException {
		try {
			clientHelper.getMessageMetadataAsDictionary(txn,
					header.getManifestId());
		} catch (NoSuchMessageException e) {
			// Without the manifest no chunk can have been delivered
			return new FileStatus(header, false, 0);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		try {
			int received = countChunks(txn, header.getGroupId(),
					header.getManifestId());
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
		// Check that the file is complete before handing out a stream, so
		// callers don't get a truncated file
		FileStatus status = getFileStatus(txn, header);
		if (!status.isComplete()) throw new NoSuchMessageException();
		return new ChunkInputStream(header);
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
			if (index < 0 || index >= header.getChunkCount())
				throw new NoSuchMessageException();
			return loadChunkPayload(txn, header, index);
		});
	}

	private byte[] loadChunkPayload(Transaction txn, FileHeader header,
			int index) throws DbException {
		MessageId id = getChunkId(txn, header.getGroupId(),
				header.getManifestId(), index);
		Message m = db.getMessage(txn, id);
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

		private final FileHeader header;
		private int next = 0;
		private InputStream current = new ByteArrayInputStream(new byte[0]);

		private ChunkInputStream(FileHeader header) {
			this.header = header;
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
				if (next >= header.getChunkCount()) return -1;
				current = loadChunk(next++);
			}
		}

		private InputStream loadChunk(int index) throws IOException {
			try {
				return db.transactionWithResult(true, txn ->
						new ByteArrayInputStream(
								loadChunkPayload(txn, header, index)));
			} catch (DbException e) {
				throw new IOException(e);
			}
		}
	}

	// Receiving files. A manifest may arrive before or after the message
	// that references it, so it keeps a cleanup timer until that message
	// makes the file wanted. Chunks depend on their manifest, so they are
	// delivered only after it, and are checked against it on delivery.

	/**
	 * Handles an incoming manifest. Call from the client's incoming message
	 * hook. No chunk of the file can have been delivered yet, as chunks
	 * depend on the manifest; they are delivered after this returns.
	 */
	public void incomingManifest(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		GroupId g = m.getGroupId();
		if (!client.isManifestReferenced(txn, g, m.getId())) {
			// Nothing references this file yet. Give the manifest a
			// deadline, which is lifted if the referencing message arrives.
			startCleanupTimer(txn, m.getId());
		}
		reportProgress(txn, g, m.getId(), meta, 0);
	}

	/**
	 * Handles an incoming chunk. Call from the client's incoming message
	 * hook. The chunk's manifest has been delivered, as the chunk depends
	 * on it. The chunk is checked against the manifest: its hash must be
	 * the one listed at its index, and its length must be the one implied
	 * by the file size.
	 *
	 * @throws InvalidMessageException If the chunk does not match the
	 * manifest, in which case the sync layer deletes it
	 */
	public void incomingChunk(Transaction txn, Message m, BdfDictionary meta)
			throws DbException, FormatException, InvalidMessageException {
		GroupId g = m.getGroupId();
		MessageId manifestId = new MessageId(meta.getRaw(KEY_FILE_MANIFEST_ID));
		int index = meta.getInt(KEY_FILE_CHUNK_INDEX);
		int descriptorLength = meta.getInt(MSG_KEY_DESCRIPTOR_LENGTH);
		BdfDictionary manifestMeta;
		try {
			manifestMeta = clientHelper.getMessageMetadataAsDictionary(txn,
					manifestId);
		} catch (NoSuchMessageException e) {
			// The dependency is delivered but isn't one of our manifests
			throw new InvalidMessageException();
		}
		if (!manifestMeta.containsKey(KEY_FILE_CHUNK_HASHES))
			throw new InvalidMessageException();
		// The manifest must be in the same group as the chunk
		if (!db.getMessage(txn, manifestId).getGroupId().equals(g))
			throw new InvalidMessageException();
		BdfList hashes = manifestMeta.getList(KEY_FILE_CHUNK_HASHES);
		long size = manifestMeta.getLong(KEY_FILE_SIZE);
		if (index >= hashes.size()) throw new InvalidMessageException();
		byte[] body = m.getBody();
		int payloadLength = body.length - descriptorLength;
		if (payloadLength != getPayloadLength(size, index))
			throw new InvalidMessageException();
		byte[] expected = hashes.getRaw(index);
		byte[] actual = hashChunk(index, body, descriptorLength,
				payloadLength);
		if (!Arrays.equals(expected, actual))
			throw new InvalidMessageException();
		// The chunk is wanted if the file is. Otherwise keep it for a
		// while in case the referencing message arrives, then give up.
		if (!client.isManifestReferenced(txn, g, manifestId)) {
			startCleanupTimer(txn, m.getId());
		}
		// This chunk isn't marked as delivered until this hook returns, so
		// the count doesn't include it yet
		int received = countChunks(txn, g, manifestId) + 1;
		reportProgress(txn, g, manifestId, manifestMeta, received);
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
		Collection<MessageId> presentManifests = clientHelper.getMessageIds(
				txn, g, client.getManifestQuery());
		for (MessageId manifestId : manifestIds) {
			if (!presentManifests.contains(manifestId)) continue;
			db.stopCleanupTimer(txn, manifestId);
			// The chunks that are already here are wanted now too. A chunk
			// that arrives later finds a referenced manifest and isn't given
			// a timer at all.
			for (MessageId chunkId : getChunkIds(txn, g, manifestId)) {
				db.stopCleanupTimer(txn, chunkId);
			}
		}
	}

	private void startCleanupTimer(Transaction txn, MessageId m)
			throws DbException {
		db.setCleanupTimerDuration(txn, m,
				client.getMissingFileCleanupDurationMs());
		db.startCleanupTimer(txn, m);
	}

	private void reportProgress(Transaction txn, GroupId g,
			MessageId manifestId, BdfDictionary manifestMeta, int received)
			throws FormatException {
		int total = manifestMeta.getList(KEY_FILE_CHUNK_HASHES).size();
		txn.attach(new FileProgressEvent(g, manifestId, received, total));
	}
}
