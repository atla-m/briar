package org.briarproject.briar.api.privategroup;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.client.ThreadedMessage;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static java.util.Collections.emptyList;

@Immutable
@NotNullByDefault
public class GroupMessage extends ThreadedMessage {

	private final boolean hasText;
	private final List<AttachmentHeader> attachmentHeaders;
	private final List<GroupFileHeader> fileHeaders;

	public GroupMessage(Message message, @Nullable MessageId parent,
			Author member) {
		this(message, parent, member, true, emptyList(), emptyList());
	}

	public GroupMessage(Message message, @Nullable MessageId parent,
			Author member, boolean hasText,
			List<AttachmentHeader> attachmentHeaders) {
		this(message, parent, member, hasText, attachmentHeaders, emptyList());
	}

	public GroupMessage(Message message, @Nullable MessageId parent,
			Author member, boolean hasText,
			List<AttachmentHeader> attachmentHeaders,
			List<GroupFileHeader> fileHeaders) {
		super(message, parent, member);
		this.hasText = hasText;
		this.attachmentHeaders = attachmentHeaders;
		this.fileHeaders = fileHeaders;
	}

	public Author getMember() {
		return super.getAuthor();
	}

	/**
	 * Returns true if the message has text. A message without text must
	 * have at least one attachment or file.
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
