package org.briarproject.briar.android.conversation;

import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

@NotThreadSafe
@NotNullByDefault
class ConversationMessageItem extends ConversationItem {

	private final List<AttachmentItem> attachments;
	private final List<FileHeader> fileHeaders;
	// How much of each shared file has arrived, keyed by manifest ID.
	// Empty until the status has been loaded.
	private final Map<MessageId, FileStatus> fileStatuses = new HashMap<>();

	ConversationMessageItem(@LayoutRes int layoutRes, PrivateMessageHeader h,
			LiveData<String> contactName, List<AttachmentItem> attachments) {
		super(layoutRes, h, contactName);
		this.attachments = attachments;
		this.fileHeaders = h.getFileHeaders();
	}

	/**
	 * Returns the headers of the files (audio, video, documents) shared by
	 * the message. Images are attachments, not files, even when chunked.
	 */
	List<FileHeader> getFileHeaders() {
		return fileHeaders;
	}

	@Nullable
	FileStatus getFileStatus(FileHeader header) {
		return fileStatuses.get(header.getManifestId());
	}

	/**
	 * Returns true if the message shares the file with the given manifest.
	 */
	boolean hasFile(MessageId manifestId) {
		for (FileHeader h : fileHeaders) {
			if (h.getManifestId().equals(manifestId)) return true;
		}
		return false;
	}

	/**
	 * Records the status of one of the message's files. Returns true if
	 * the status changed, so the message needs to be redrawn.
	 */
	@UiThread
	boolean updateFileStatus(FileStatus status) {
		MessageId manifestId = status.getHeader().getManifestId();
		FileStatus old = fileStatuses.put(manifestId, status);
		return old == null ||
				old.getChunksReceived() != status.getChunksReceived() ||
				old.isManifestReceived() != status.isManifestReceived();
	}

	List<AttachmentItem> getAttachments() {
		return attachments;
	}

	@UiThread
	boolean updateAttachments(AttachmentItem item) {
		int pos = attachments.indexOf(item);
		if (pos != -1 && attachments.get(pos).getState() != item.getState()) {
			attachments.set(pos, item);
			return true;
		}
		return false;
	}

}
