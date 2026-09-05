package org.briarproject.briar.android.privategroup.conversation;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.threaded.ThreadItem;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.privategroup.GroupMessageHeader;

import java.util.ArrayList;
import java.util.List;

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

	private GroupMessageItem(MessageId messageId, GroupId groupId,
			@Nullable MessageId parentId, String text, long timestamp,
			Author author, AuthorInfo authorInfo, boolean isRead,
			boolean hasText, List<AttachmentHeader> attachmentHeaders) {
		super(messageId, parentId, text, timestamp, author, authorInfo, isRead);
		this.groupId = groupId;
		this.hasText = hasText;
		this.attachmentHeaders = attachmentHeaders;
	}

	GroupMessageItem(GroupMessageHeader h, String text) {
		this(h.getId(), h.getGroupId(), h.getParentId(), text, h.getTimestamp(),
				h.getAuthor(), h.getAuthorInfo(), h.isRead(), h.hasText(),
				h.getAttachmentHeaders());
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

	@LayoutRes
	public int getLayout() {
		return R.layout.list_item_group_post;
	}

}
