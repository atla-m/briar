package org.briarproject.briar.api.privategroup.event;

import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * An event that is broadcast when a chunk or the manifest of a shared file
 * is received, so that progress can be shown as the file arrives piece by
 * piece.
 */
@Immutable
@NotNullByDefault
public class GroupFileProgressEvent extends Event {

	private final GroupId groupId;
	private final MessageId manifestId;
	private final int chunksReceived, chunkCount;

	public GroupFileProgressEvent(GroupId groupId, MessageId manifestId,
			int chunksReceived, int chunkCount) {
		this.groupId = groupId;
		this.manifestId = manifestId;
		this.chunksReceived = chunksReceived;
		this.chunkCount = chunkCount;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public MessageId getManifestId() {
		return manifestId;
	}

	public int getChunksReceived() {
		return chunksReceived;
	}

	public int getChunkCount() {
		return chunkCount;
	}

	public boolean isComplete() {
		return chunksReceived == chunkCount;
	}
}
