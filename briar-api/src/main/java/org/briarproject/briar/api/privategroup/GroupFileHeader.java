package org.briarproject.briar.api.privategroup;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * Describes a file shared in a private group. The header is carried by the
 * post that shares the file, so members learn the file's name and size
 * before any of its bytes arrive. The manifest ID identifies the
 * {@link MessageType#FILE_MANIFEST} message that lists the file's chunks.
 */
@Immutable
@NotNullByDefault
public class GroupFileHeader {

	private final GroupId groupId;
	private final MessageId manifestId;
	private final String name, contentType;
	private final long size;

	public GroupFileHeader(GroupId groupId, MessageId manifestId, String name,
			String contentType, long size) {
		this.groupId = groupId;
		this.manifestId = manifestId;
		this.name = name;
		this.contentType = contentType;
		this.size = size;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public MessageId getManifestId() {
		return manifestId;
	}

	public String getName() {
		return name;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSize() {
		return size;
	}

	/**
	 * Returns the number of chunks the file is split into.
	 */
	public int getChunkCount() {
		int payload = PrivateGroupConstants.FILE_CHUNK_PAYLOAD_LENGTH;
		return (int) ((size + payload - 1) / payload);
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof GroupFileHeader) {
			GroupFileHeader h = (GroupFileHeader) o;
			return groupId.equals(h.groupId) && manifestId.equals(h.manifestId);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return manifestId.hashCode();
	}
}
