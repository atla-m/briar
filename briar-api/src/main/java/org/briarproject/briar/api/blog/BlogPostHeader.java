package org.briarproject.briar.api.blog;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.client.PostHeader;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

@Immutable
@NotNullByDefault
public class BlogPostHeader extends PostHeader {

	private final MessageType type;
	private final GroupId groupId;
	private final long timeReceived;
	private final boolean rssFeed;
	private final List<AttachmentHeader> attachmentHeaders;
	private final List<FileHeader> fileHeaders;
	private final int attachmentsNotCarried;

	public BlogPostHeader(MessageType type, GroupId groupId, MessageId id,
			@Nullable MessageId parentId, long timestamp, long timeReceived,
			Author author, AuthorInfo authorInfo, boolean rssFeed,
			boolean read, List<AttachmentHeader> attachmentHeaders,
			List<FileHeader> fileHeaders, int attachmentsNotCarried) {
		super(id, parentId, timestamp, author, authorInfo, read);
		this.type = type;
		this.groupId = groupId;
		this.timeReceived = timeReceived;
		this.rssFeed = rssFeed;
		this.attachmentHeaders = attachmentHeaders;
		this.fileHeaders = fileHeaders;
		this.attachmentsNotCarried = attachmentsNotCarried;
	}

	public BlogPostHeader(MessageType type, GroupId groupId, MessageId id,
			@Nullable MessageId parentId, long timestamp, long timeReceived,
			Author author, AuthorInfo authorInfo, boolean rssFeed,
			boolean read, List<AttachmentHeader> attachmentHeaders,
			List<FileHeader> fileHeaders) {
		this(type, groupId, id, parentId, timestamp, timeReceived, author,
				authorInfo, rssFeed, read, attachmentHeaders, fileHeaders, 0);
	}

	public BlogPostHeader(MessageType type, GroupId groupId, MessageId id,
			@Nullable MessageId parentId, long timestamp, long timeReceived,
			Author author, AuthorInfo authorInfo, boolean rssFeed, boolean read) {
		this(type, groupId, id, parentId, timestamp, timeReceived, author,
				authorInfo, rssFeed, read, emptyList(), emptyList());
	}

	public BlogPostHeader(MessageType type, GroupId groupId, MessageId id,
			long timestamp, long timeReceived, Author author,
			AuthorInfo authorInfo, boolean rssFeed, boolean read) {
		this(type, groupId, id, null, timestamp, timeReceived, author,
				authorInfo, rssFeed, read);
	}

	public MessageType getType() {
		return type;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public long getTimeReceived() {
		return timeReceived;
	}

	public boolean isRssFeed() {
		return rssFeed;
	}

	/**
	 * Returns the headers of the images this post carries, in the order
	 * they were attached.
	 */
	public List<AttachmentHeader> getAttachmentHeaders() {
		return unmodifiableList(attachmentHeaders);
	}

	/**
	 * Returns the headers of the files this post shares. A file's name and
	 * size are known from the header before the file itself has arrived.
	 */
	public List<FileHeader> getFileHeaders() {
		return unmodifiableList(fileHeaders);
	}

	/**
	 * Returns how many images and files the original post carried that
	 * this copy of it does not. A reblogged post is a signed copy of the
	 * original, and the signature covers its attachments, but they live
	 * in the original blog's group and cannot be read from the blog the
	 * copy is in. Zero for a post that is not a copy.
	 */
	public int getAttachmentsNotCarried() {
		return attachmentsNotCarried;
	}

}
