package org.briarproject.briar.api.privategroup.event;

import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * An event that is broadcast when an attachment is received in a private
 * group. The post that references the attachment may arrive before or after
 * the attachment itself.
 */
@Immutable
@NotNullByDefault
public class GroupAttachmentReceivedEvent extends Event {

	private final GroupId groupId;
	private final MessageId messageId;

	public GroupAttachmentReceivedEvent(GroupId groupId, MessageId messageId) {
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
