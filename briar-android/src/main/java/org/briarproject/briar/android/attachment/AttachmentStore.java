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

	/**
	 * Returns the maximum size of an attachment this store accepts for the
	 * given group, in bytes. Images larger than this are compressed to fit
	 * before being stored. Stores that split attachments into chunks can
	 * accept far more than a single message, so most images keep their
	 * original quality; whether that's possible may depend on the client
	 * version of the other side, hence the group.
	 */
	long getMaxAttachmentSize(GroupId groupId);

}
