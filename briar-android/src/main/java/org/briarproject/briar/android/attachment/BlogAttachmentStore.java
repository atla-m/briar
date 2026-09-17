package org.briarproject.briar.android.attachment;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link AttachmentStore} for the images and files carried by blog and
 * channel posts. A blog has no single recipient whose client version we
 * could ask, and posts carrying attachments are a new message format in any
 * case, so large images are always chunked and keep their quality.
 */
@NotNullByDefault
public class BlogAttachmentStore extends ChunkingAttachmentStore {

	private final BlogManager blogManager;

	public BlogAttachmentStore(BlogManager blogManager) {
		this.blogManager = blogManager;
	}

	@Override
	protected AttachmentHeader addSingleAttachment(GroupId groupId,
			long timestamp, String contentType, InputStream in)
			throws DbException, IOException {
		return blogManager.addLocalAttachment(groupId, timestamp, contentType,
				in);
	}

	@Override
	protected void removeSingleAttachment(AttachmentHeader header)
			throws DbException {
		blogManager.removeAttachment(header);
	}

	@Override
	protected FileHeader addFile(GroupId groupId, long timestamp, String name,
			String contentType, StreamSource source)
			throws DbException, IOException {
		return blogManager.addLocalFile(groupId, timestamp, name, contentType,
				source);
	}

	@Override
	protected FileHeader getFileHeader(GroupId groupId, MessageId manifestId)
			throws DbException {
		return blogManager.getFileHeader(groupId, manifestId);
	}

	@Override
	protected void removeFile(FileHeader header) throws DbException {
		blogManager.removeFile(header);
	}

	@Override
	protected boolean supportsFiles(GroupId groupId) {
		return true;
	}
}
