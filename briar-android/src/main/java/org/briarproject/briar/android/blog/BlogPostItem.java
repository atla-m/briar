package org.briarproject.briar.android.blog;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.attachment.ImageGridAdapter;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.blog.BlogPostHeader;
import org.briarproject.briar.api.identity.AuthorInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.NonNull;

@NotThreadSafe
public class BlogPostItem
		implements Comparable<BlogPostItem>, ImageGridAdapter.Item {

	private final BlogPostHeader header;
	@Nullable
	protected String text;
	private final boolean read;
	private final List<AttachmentItem> attachments = new ArrayList<>();
	// The status of each of the post's files, keyed by manifest ID, filled
	// in as the file arrives
	private final Map<MessageId, FileStatus> fileStatuses = new HashMap<>();

	BlogPostItem(BlogPostHeader header, @Nullable String text) {
		this.header = header;
		this.text = text;
		this.read = header.isRead();
	}

	@Override
	public MessageId getId() {
		return header.getId();
	}

	public GroupId getGroupId() {
		return header.getGroupId();
	}

	public long getTimestamp() {
		return header.getTimestamp();
	}

	public Author getAuthor() {
		return header.getAuthor();
	}

	AuthorInfo getAuthorInfo() {
		return header.getAuthorInfo();
	}

	@Nullable
	public String getText() {
		return text;
	}

	boolean isRssFeed() {
		return header.isRssFeed();
	}

	public boolean isRead() {
		return read;
	}

	public BlogPostHeader getHeader() {
		return header;
	}

	/**
	 * Returns the headers of the images the post carries.
	 */
	public List<AttachmentHeader> getAttachmentHeaders() {
		return getPostHeader().getAttachmentHeaders();
	}

	/**
	 * Returns the image items, which are empty until
	 * {@link #setAttachments(List)} has been called.
	 */
	@Override
	public List<AttachmentItem> getAttachments() {
		return attachments;
	}

	void setAttachments(List<AttachmentItem> items) {
		attachments.clear();
		attachments.addAll(items);
	}

	/**
	 * Replaces the image item with the same ID as the given item, if its
	 * state has changed. Returns true if an item was replaced.
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
	 * Returns the headers of the files the post shares. Images are
	 * attachments, not files, even when they are chunked.
	 */
	public List<FileHeader> getFileHeaders() {
		return getPostHeader().getFileHeaders();
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

	BlogPostHeader getPostHeader() {
		return getHeader();
	}

	@Override
	public int compareTo(@NonNull BlogPostItem other) {
		if (this == other) return 0;
		return compare(getHeader(), other.getHeader());
	}

	protected static int compare(BlogPostHeader h1, BlogPostHeader h2) {
		// The newest post comes first
		return Long.compare(h2.getTimeReceived(), h1.getTimeReceived());
	}
}
