package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stores and removes attachments for a particular client, so that the
 * attachment creation code can be shared between private messaging and
 * private groups.
 */
@NotNullByDefault
public interface AttachmentStore {

	AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream in)
			throws DbException, IOException;

	void removeAttachment(AttachmentHeader header) throws DbException;

}
