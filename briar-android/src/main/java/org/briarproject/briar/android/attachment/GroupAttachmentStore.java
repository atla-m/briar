package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link AttachmentStore} for attachments to private group posts. All
 * members of a group we can post to run a client with file support, so
 * large images are always chunked.
 */
@NotNullByDefault
public class GroupAttachmentStore extends ChunkingAttachmentStore {

	private final PrivateGroupManager privateGroupManager;

	public GroupAttachmentStore(PrivateGroupManager privateGroupManager) {
		this.privateGroupManager = privateGroupManager;
	}

	@Override
	protected AttachmentHeader addSingleAttachment(GroupId groupId,
			long timestamp, String contentType, InputStream in)
			throws DbException, IOException {
		return privateGroupManager.addLocalAttachment(groupId, timestamp,
				contentType, in);
	}

	@Override
	protected void removeSingleAttachment(AttachmentHeader header)
			throws DbException {
		privateGroupManager.removeAttachment(header);
	}

	@Override
	protected FileHeader addFile(GroupId groupId, long timestamp, String name,
			String contentType, InputStream in)
			throws DbException, IOException {
		return privateGroupManager.addLocalFile(groupId, timestamp, name,
				contentType, in);
	}

	@Override
	protected FileHeader getFileHeader(GroupId groupId, MessageId manifestId)
			throws DbException {
		return privateGroupManager.getFileHeader(groupId, manifestId);
	}

	@Override
	protected void removeFile(FileHeader header) throws DbException {
		privateGroupManager.removeFile(header);
	}

	@Override
	protected boolean supportsFiles(GroupId groupId) {
		return true;
	}
}
