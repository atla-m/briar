package org.briarproject.briar.android.privategroup.conversation;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.threaded.ThreadItem;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.privategroup.GroupMessageHeader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.annotation.UiThread;

@UiThread
@NotThreadSafe
class GroupMessageItem extends ThreadItem {

	private final GroupId groupId;
	private final boolean hasText;
	private final List<AttachmentHeader> attachmentHeaders;
	private final List<AttachmentItem> attachments = new ArrayList<>();
	private final List<FileHeader> fileHeaders;
	// How much of each shared file has arrived, keyed by manifest ID.
	// Empty until the status has been loaded.
	private final Map<MessageId, FileStatus> fileStatuses =
			new HashMap<>();

	private GroupMessageItem(MessageId messageId, GroupId groupId,
			@Nullable MessageId parentId, String text, long timestamp,
			Author author, AuthorInfo authorInfo, boolean isRead,
			boolean hasText, List<AttachmentHeader> attachmentHeaders,
			List<FileHeader> fileHeaders) {
		super(messageId, parentId, text, timestamp, author, authorInfo, isRead);
		this.groupId = groupId;
		this.hasText = hasText;
		this.attachmentHeaders = attachmentHeaders;
		this.fileHeaders = fileHeaders;
	}

	GroupMessageItem(GroupMessageHeader h, String text) {
		this(h.getId(), h.getGroupId(), h.getParentId(), text, h.getTimestamp(),
				h.getAuthor(), h.getAuthorInfo(), h.isRead(), h.hasText(),
				h.getAttachmentHeaders(), h.getFileHeaders());
	}

	public GroupId getGroupId() {
		return groupId;
	}

	/**
	 * Returns true if the post has text. A post without text has at least
	 * one attachment.
	 */
	boolean hasText() {
		return hasText;
	}

	List<AttachmentHeader> getAttachmentHeaders() {
		return attachmentHeaders;
	}

	/**
	 * Returns the attachment items, which are empty until
	 * {@link #setAttachments(List)} has been called.
	 */
	List<AttachmentItem> getAttachments() {
		return attachments;
	}

	void setAttachments(List<AttachmentItem> items) {
		attachments.clear();
		attachments.addAll(items);
	}

	/**
	 * Replaces the attachment item with the same ID as the given item, if
	 * its state has changed. Returns true if an item was replaced.
	 */
	boolean updateAttachments(AttachmentItem item) {
		int pos = attachments.indexOf(item);
		if (pos != -1 && attachments.get(pos).getState() != item.getState()) {
			attachments.set(pos, item);
			return true;
		}
		return false;
	}

	/**
	 * Returns the headers of the files (audio, video) shared by the post.
	 * Images are attachments, not files, even when they are chunked.
	 */
	List<FileHeader> getFileHeaders() {
		return fileHeaders;
	}

	/**
	 * Returns how much of the given file has arrived, or null if the
	 * status hasn't been loaded yet.
	 */
	@Nullable
	FileStatus getFileStatus(FileHeader header) {
		return fileStatuses.get(header.getManifestId());
	}

	/**
	 * Records the status of one of the post's files. Returns true if the
	 * status changed, so the post needs to be redrawn.
	 */
	boolean updateFileStatus(FileStatus status) {
		MessageId manifestId = status.getHeader().getManifestId();
		FileStatus old = fileStatuses.put(manifestId, status);
		return old == null ||
				old.getChunksReceived() != status.getChunksReceived() ||
				old.isManifestReceived() != status.isManifestReceived();
	}

	@LayoutRes
	public int getLayout() {
		return R.layout.list_item_group_post;
	}

}
