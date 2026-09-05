package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link AttachmentStore} for attachments to private messages.
 */
@NotNullByDefault
public class MessagingAttachmentStore implements AttachmentStore {

	private final MessagingManager messagingManager;

	public MessagingAttachmentStore(MessagingManager messagingManager) {
		this.messagingManager = messagingManager;
	}

	@Override
	public AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream in)
			throws DbException, IOException {
		return messagingManager.addLocalAttachment(groupId, timestamp,
				contentType, in);
	}

	@Override
	public void removeAttachment(AttachmentHeader header) throws DbException {
		messagingManager.removeAttachment(header);
	}
}
