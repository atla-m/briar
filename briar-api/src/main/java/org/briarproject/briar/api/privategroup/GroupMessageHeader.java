package org.briarproject.briar.api.privategroup;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.client.PostHeader;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static java.util.Collections.emptyList;

@Immutable
@NotNullByDefault
public class GroupMessageHeader extends PostHeader {

	private final GroupId groupId;
	private final boolean hasText;
	private final List<AttachmentHeader> attachmentHeaders;
	private final List<GroupFileHeader> fileHeaders;

	public GroupMessageHeader(GroupId groupId, MessageId id,
			@Nullable MessageId parentId, long timestamp,
			Author author, AuthorInfo authorInfo, boolean read) {
		this(groupId, id, parentId, timestamp, author, authorInfo, read, true,
				emptyList(), emptyList());
	}

	public GroupMessageHeader(GroupId groupId, MessageId id,
			@Nullable MessageId parentId, long timestamp,
			Author author, AuthorInfo authorInfo, boolean read,
			boolean hasText, List<AttachmentHeader> attachmentHeaders) {
		this(groupId, id, parentId, timestamp, author, authorInfo, read,
				hasText, attachmentHeaders, emptyList());
	}

	public GroupMessageHeader(GroupId groupId, MessageId id,
			@Nullable MessageId parentId, long timestamp,
			Author author, AuthorInfo authorInfo, boolean read,
			boolean hasText, List<AttachmentHeader> attachmentHeaders,
			List<GroupFileHeader> fileHeaders) {
		super(id, parentId, timestamp, author, authorInfo, read);
		this.groupId = groupId;
		this.hasText = hasText;
		this.attachmentHeaders = attachmentHeaders;
		this.fileHeaders = fileHeaders;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	/**
	 * Returns true if the message has text. A message without text has at
	 * least one attachment.
	 */
	public boolean hasText() {
		return hasText;
	}

	/**
	 * Returns the headers of the message's attachments, if any.
	 */
	public List<AttachmentHeader> getAttachmentHeaders() {
		return attachmentHeaders;
	}

	/**
	 * Returns the headers of the files shared by the message, if any.
	 */
	public List<GroupFileHeader> getFileHeaders() {
		return fileHeaders;
	}

}
