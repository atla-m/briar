package org.briarproject.briar.messaging;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.attachment.ChunkedFileStore;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.briar.messaging.MessageTypes.FILE_CHUNK;
import static org.briarproject.briar.messaging.MessageTypes.FILE_MANIFEST;
import static org.briarproject.briar.messaging.MessageTypes.FILE_REQUEST;
import static org.briarproject.briar.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.briarproject.briar.messaging.MessagingConstants.MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;

/**
 * Tells the shared file store how the messaging client identifies its own
 * manifests and chunks, and which of its messages reference a file.
 */
@Immutable
@NotNullByDefault
class MessagingFileClient implements ChunkedFileStore.Client {

	private final ClientHelper clientHelper;

	MessagingFileClient(ClientHelper clientHelper) {
		this.clientHelper = clientHelper;
	}

	@Override
	public int getManifestType() {
		return FILE_MANIFEST;
	}

	@Override
	public int getChunkType() {
		return FILE_CHUNK;
	}

	@Override
	public int getRequestType() {
		return FILE_REQUEST;
	}

	@Override
	public BdfDictionary getLocalFileMetadata(int messageType,
			long timestamp) {
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, timestamp);
		meta.put(MSG_KEY_LOCAL, true);
		meta.put(MSG_KEY_MSG_TYPE, messageType);
		return meta;
	}

	@Override
	public BdfDictionary getManifestQuery() {
		return BdfDictionary.of(new BdfEntry(MSG_KEY_MSG_TYPE, FILE_MANIFEST));
	}

	@Override
	public BdfDictionary getChunkQuery() {
		return BdfDictionary.of(new BdfEntry(MSG_KEY_MSG_TYPE, FILE_CHUNK));
	}

	@Override
	public boolean isManifestReferenced(Transaction txn, GroupId g,
			MessageId manifestId) throws DbException, FormatException {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE));
		Map<MessageId, BdfDictionary> messages =
				clientHelper.getMessageMetadataAsDictionary(txn, g, query);
		for (BdfDictionary meta : messages.values()) {
			if (getReferencedIds(meta).contains(manifestId)) return true;
		}
		return false;
	}

	@Override
	public long getMissingFileCleanupDurationMs() {
		return MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
	}

	/**
	 * Returns the IDs of the attachments and file manifests referenced by
	 * the message with the given metadata.
	 */
	Set<MessageId> getReferencedIds(BdfDictionary meta)
			throws FormatException {
		Set<MessageId> ids = new HashSet<>();
		if (!meta.containsKey(MSG_KEY_ATTACHMENT_HEADERS)) return ids;
		BdfList list = meta.getList(MSG_KEY_ATTACHMENT_HEADERS);
		for (int i = 0; i < list.size(); i++) {
			ids.add(new MessageId(list.getList(i).getRaw(0)));
		}
		return ids;
	}
}
