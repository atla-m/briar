package org.briarproject.briar.api.attachment.event;

import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * An event that is broadcast when the user asks for a file that was held
 * back, so that any other way of getting it, such as a channel's mirrors,
 * can be tried as well as asking contacts.
 */
@Immutable
@NotNullByDefault
public class FileRequestedEvent extends Event {

	private final GroupId groupId;
	private final MessageId manifestId;

	public FileRequestedEvent(GroupId groupId, MessageId manifestId) {
		this.groupId = groupId;
		this.manifestId = manifestId;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public MessageId getManifestId() {
		return manifestId;
	}
}
