package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.privategroup.GroupFileHeader;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_IMAGE_SIZE;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_FILE_SIZE;

/**
 * An {@link AttachmentStore} for attachments to private group posts. Images
 * that fit into a single message are stored as ordinary attachments. Larger
 * images are stored as chunked files, so they keep their original quality
 * and can be transferred piece by piece. Either way the post references the
 * image by a single message ID, which for a chunked image is the ID of its
 * manifest.
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
		// Read the image to find out whether it fits into one message
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyAndClose(in, out);
		byte[] bytes = out.toByteArray();
		if (bytes.length <= MAX_IMAGE_SIZE) {
			return privateGroupManager.addLocalAttachment(groupId, timestamp,
					contentType, new ByteArrayInputStream(bytes));
		}
		String name = "image." + getExtension(contentType);
		GroupFileHeader file = privateGroupManager.addLocalFile(groupId,
				timestamp, name, contentType, new ByteArrayInputStream(bytes));
		return new AttachmentHeader(groupId, file.getManifestId(), contentType);
	}

	private String getExtension(String contentType) {
		int slash = contentType.indexOf('/');
		if (slash == -1 || slash == contentType.length() - 1) return "bin";
		String subtype = contentType.substring(slash + 1);
		return subtype.equals("jpeg") ? "jpg" : subtype;
	}

	@Override
	public void removeAttachment(AttachmentHeader header) throws DbException {
		// The header may point at a manifest, in which case the chunks are
		// removed along with it, or at a single attachment message
		try {
			GroupFileHeader file = privateGroupManager.getFileHeader(
					header.getGroupId(), header.getMessageId());
			privateGroupManager.removeFile(file);
		} catch (org.briarproject.bramble.api.db.NoSuchMessageException e) {
			privateGroupManager.removeAttachment(header);
		}
	}

	@Override
	public long getMaxAttachmentSize() {
		// Large images are chunked, so only very large ones need compressing
		return MAX_GROUP_FILE_SIZE;
	}
}
