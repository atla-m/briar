package org.briarproject.briar.blog;

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

import static org.briarproject.briar.api.blog.BlogConstants.KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_TIMESTAMP;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_TYPE;
import static org.briarproject.briar.api.blog.BlogConstants.MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
import static org.briarproject.briar.api.blog.MessageType.FILE_CHUNK;
import static org.briarproject.briar.api.blog.MessageType.FILE_MANIFEST;
import static org.briarproject.briar.api.blog.MessageType.FILE_REQUEST;
import static org.briarproject.briar.api.blog.MessageType.POST;

/**
 * Tells the shared file store how the blog client identifies its own
 * manifests and chunks, and which of its posts reference a file.
 */
@Immutable
@NotNullByDefault
class BlogFileClient implements ChunkedFileStore.Client {

	private final ClientHelper clientHelper;

	BlogFileClient(ClientHelper clientHelper) {
		this.clientHelper = clientHelper;
	}

	@Override
	public int getManifestType() {
		return FILE_MANIFEST.getInt();
	}

	@Override
	public int getChunkType() {
		return FILE_CHUNK.getInt();
	}

	@Override
	public int getRequestType() {
		return FILE_REQUEST.getInt();
	}

	@Override
	public BdfDictionary getLocalFileMetadata(int messageType,
			long timestamp) {
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_TYPE, messageType);
		meta.put(KEY_TIMESTAMP, timestamp);
		return meta;
	}

	@Override
	public BdfDictionary getManifestQuery() {
		return BdfDictionary.of(
				new BdfEntry(KEY_TYPE, FILE_MANIFEST.getInt()));
	}

	@Override
	public BdfDictionary getChunkQuery() {
		return BdfDictionary.of(new BdfEntry(KEY_TYPE, FILE_CHUNK.getInt()));
	}

	@Override
	public boolean isManifestReferenced(Transaction txn, GroupId g,
			MessageId manifestId) throws DbException, FormatException {
		BdfDictionary query =
				BdfDictionary.of(new BdfEntry(KEY_TYPE, POST.getInt()));
		Map<MessageId, BdfDictionary> posts =
				clientHelper.getMessageMetadataAsDictionary(txn, g, query);
		for (BdfDictionary meta : posts.values()) {
			if (getReferencedIds(meta).contains(manifestId)) return true;
		}
		return false;
	}

	@Override
	public long getMissingFileCleanupDurationMs() {
		return MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
	}

	/**
	 * Returns the IDs of the images and file manifests referenced by the
	 * post with the given metadata.
	 */
	Set<MessageId> getReferencedIds(BdfDictionary postMeta)
			throws FormatException {
		Set<MessageId> ids = new HashSet<>();
		if (!postMeta.containsKey(KEY_ATTACHMENT_HEADERS)) return ids;
		BdfList list = postMeta.getList(KEY_ATTACHMENT_HEADERS);
		for (int i = 0; i < list.size(); i++) {
			ids.add(new MessageId(list.getList(i).getRaw(0)));
		}
		return ids;
	}
}
