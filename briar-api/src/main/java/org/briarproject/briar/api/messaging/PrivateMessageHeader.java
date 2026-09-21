package org.briarproject.briar.api.messaging;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.conversation.ConversationMessageVisitor;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import static java.util.Collections.emptyList;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PrivateMessageHeader extends ConversationMessageHeader {

	private final boolean hasText;
	private final List<AttachmentHeader> attachmentHeaders;
	private final List<FileHeader> fileHeaders;
	@Nullable
	private final String channelLink;

	public PrivateMessageHeader(MessageId id, GroupId groupId, long timestamp,
			boolean local, boolean read, boolean sent, boolean seen,
			boolean hasText, List<AttachmentHeader> headers,
			long autoDeleteTimer) {
		this(id, groupId, timestamp, local, read, sent, seen, hasText,
				headers, emptyList(), autoDeleteTimer);
	}

	public PrivateMessageHeader(MessageId id, GroupId groupId, long timestamp,
			boolean local, boolean read, boolean sent, boolean seen,
			boolean hasText, List<AttachmentHeader> headers,
			List<FileHeader> fileHeaders, long autoDeleteTimer) {
		this(id, groupId, timestamp, local, read, sent, seen, hasText,
				headers, fileHeaders, autoDeleteTimer, null);
	}

	public PrivateMessageHeader(MessageId id, GroupId groupId, long timestamp,
			boolean local, boolean read, boolean sent, boolean seen,
			boolean hasText, List<AttachmentHeader> headers,
			List<FileHeader> fileHeaders, long autoDeleteTimer,
			@Nullable String channelLink) {
		super(id, groupId, timestamp, local, read, sent, seen, autoDeleteTimer);
		this.hasText = hasText;
		this.attachmentHeaders = headers;
		this.fileHeaders = fileHeaders;
		this.channelLink = channelLink;
	}

	public boolean hasText() {
		return hasText;
	}

	public List<AttachmentHeader> getAttachmentHeaders() {
		return attachmentHeaders;
	}

	/**
	 * Returns the headers of the files shared by the message, if any.
	 */
	public List<FileHeader> getFileHeaders() {
		return fileHeaders;
	}

	/**
	 * Returns the link of the channel this post was forwarded from, or
	 * null if the message is not a forward.
	 */
	@Nullable
	public String getChannelLink() {
		return channelLink;
	}

	@Override
	public <T> T accept(ConversationMessageVisitor<T> v) {
		return v.visitPrivateMessageHeader(this);
	}
}
