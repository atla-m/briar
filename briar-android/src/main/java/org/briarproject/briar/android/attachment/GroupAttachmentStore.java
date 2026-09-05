package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link AttachmentStore} for attachments to private group posts.
 */
@NotNullByDefault
public class GroupAttachmentStore implements AttachmentStore {

	private final PrivateGroupManager privateGroupManager;

	public GroupAttachmentStore(PrivateGroupManager privateGroupManager) {
		this.privateGroupManager = privateGroupManager;
	}

	@Override
	public AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream in)
			throws DbException, IOException {
		return privateGroupManager.addLocalAttachment(groupId, timestamp,
				contentType, in);
	}

	@Override
	public void removeAttachment(AttachmentHeader header) throws DbException {
		privateGroupManager.removeAttachment(header);
	}
}
