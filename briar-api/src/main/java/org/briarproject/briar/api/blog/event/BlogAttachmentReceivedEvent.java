package org.briarproject.briar.api.blog.event;

import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * An event that is broadcast when an image carried by a blog or channel
 * post is received. The post that references the image may arrive before or
 * after the image itself.
 */
@Immutable
@NotNullByDefault
public class BlogAttachmentReceivedEvent extends Event {

	private final GroupId groupId;
	private final MessageId messageId;

	public BlogAttachmentReceivedEvent(GroupId groupId, MessageId messageId) {
		this.groupId = groupId;
		this.messageId = messageId;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public MessageId getMessageId() {
		return messageId;
	}
}
