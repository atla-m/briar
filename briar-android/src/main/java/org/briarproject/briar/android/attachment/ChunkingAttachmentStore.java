package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_IMAGE_SIZE;

/**
 * An {@link AttachmentStore} that keeps images fitting into a single
 * message as ordinary attachments and stores larger images as chunked
 * files, so they keep their original quality and can be transferred piece
 * by piece. Either way the message references the image by a single message
 * ID, which for a chunked image is the ID of its manifest. Subclasses supply
 * the client that stores the messages.
 */
@NotNullByDefault
abstract class ChunkingAttachmentStore implements AttachmentStore {

	protected abstract AttachmentHeader addSingleAttachment(GroupId groupId,
			long timestamp, String contentType, InputStream in)
			throws DbException, IOException;

	protected abstract void removeSingleAttachment(AttachmentHeader header)
			throws DbException;

	protected abstract FileHeader addFile(GroupId groupId, long timestamp,
			String name, String contentType, InputStream in)
			throws DbException, IOException;

	protected abstract FileHeader getFileHeader(GroupId groupId,
			MessageId manifestId) throws DbException;

	protected abstract void removeFile(FileHeader header) throws DbException;

	/**
	 * Returns true if the other side of the given group can receive chunked
	 * files.
	 */
	protected abstract boolean supportsFiles(GroupId groupId)
			throws DbException;

	@Override
	public AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream in)
			throws DbException, IOException {
		// Read the image to find out whether it fits into one message
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyAndClose(in, out);
		byte[] bytes = out.toByteArray();
		if (bytes.length <= MAX_IMAGE_SIZE) {
			return addSingleAttachment(groupId, timestamp, contentType,
					new ByteArrayInputStream(bytes));
		}
		// The caller compresses images to the size we reported, so this
		// only fails if the other side stopped supporting files meanwhile
		if (!supportsFiles(groupId)) throw new FileTooBigException();
		String name = "image." + getExtension(contentType);
		FileHeader file = addFile(groupId, timestamp, name, contentType,
				new ByteArrayInputStream(bytes));
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
			FileHeader file = getFileHeader(header.getGroupId(),
					header.getMessageId());
			removeFile(file);
		} catch (NoSuchMessageException e) {
			removeSingleAttachment(header);
		}
	}

	@Override
	public long getMaxAttachmentSize(GroupId groupId) {
		// Large images are chunked, so only very large ones need
		// compressing; unless the other side can't receive chunks
		try {
			return supportsFiles(groupId) ? MAX_FILE_SIZE : MAX_IMAGE_SIZE;
		} catch (DbException e) {
			return MAX_IMAGE_SIZE;
		}
	}
}
