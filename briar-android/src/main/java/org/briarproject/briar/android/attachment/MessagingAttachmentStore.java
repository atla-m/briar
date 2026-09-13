package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link AttachmentStore} for attachments to private messages. Large
 * images are chunked if the contact's client supports files; otherwise they
 * are compressed to fit into a single message, as older clients expect.
 */
@NotNullByDefault
public class MessagingAttachmentStore extends ChunkingAttachmentStore {

	private final MessagingManager messagingManager;
	private final TransactionManager db;

	public MessagingAttachmentStore(MessagingManager messagingManager,
			TransactionManager db) {
		this.messagingManager = messagingManager;
		this.db = db;
	}

	@Override
	protected AttachmentHeader addSingleAttachment(GroupId groupId,
			long timestamp, String contentType, InputStream in)
			throws DbException, IOException {
		return messagingManager.addLocalAttachment(groupId, timestamp,
				contentType, in);
	}

	@Override
	protected void removeSingleAttachment(AttachmentHeader header)
			throws DbException {
		messagingManager.removeAttachment(header);
	}

	@Override
	protected FileHeader addFile(GroupId groupId, long timestamp, String name,
			String contentType, StreamSource source)
			throws DbException, IOException {
		return messagingManager.addLocalFile(groupId, timestamp, name,
				contentType, source);
	}

	@Override
	protected FileHeader getFileHeader(GroupId groupId, MessageId manifestId)
			throws DbException {
		return messagingManager.getFileHeader(groupId, manifestId);
	}

	@Override
	protected void removeFile(FileHeader header) throws DbException {
		messagingManager.removeFile(header);
	}

	@Override
	protected boolean supportsFiles(GroupId groupId) throws DbException {
		ContactId c = messagingManager.getContactId(groupId);
		return db.transactionWithResult(true, txn ->
				messagingManager.getContactMessageFormat(txn, c))
				.supportsFiles();
	}
}
