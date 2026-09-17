package org.briarproject.briar.api.blog;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.forum.ForumPost;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

@Immutable
@NotNullByDefault
public class BlogPost extends ForumPost {

	private final List<AttachmentHeader> attachmentHeaders;
	private final List<FileHeader> fileHeaders;
	private final boolean hasText;

	public BlogPost(Message message, @Nullable MessageId parent,
			Author author, List<AttachmentHeader> attachmentHeaders,
			List<FileHeader> fileHeaders, boolean hasText) {
		super(message, parent, author);
		this.attachmentHeaders = attachmentHeaders;
		this.fileHeaders = fileHeaders;
		this.hasText = hasText;
	}

	public BlogPost(Message message, @Nullable MessageId parent,
			Author author) {
		this(message, parent, author, emptyList(), emptyList(), true);
	}

	/**
	 * Returns the headers of the images this post carries. An image is
	 * named by the message holding it, which for a chunked image is its
	 * manifest.
	 */
	public List<AttachmentHeader> getAttachmentHeaders() {
		return unmodifiableList(attachmentHeaders);
	}

	/**
	 * Returns the headers of the files this post shares, each of which
	 * arrives as a manifest plus chunks.
	 */
	public List<FileHeader> getFileHeaders() {
		return unmodifiableList(fileHeaders);
	}

	/**
	 * Returns true if this post has text as well as attachments.
	 */
	public boolean hasText() {
		return hasText;
	}
}
