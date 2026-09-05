package org.briarproject.briar.api.privategroup;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * How much of a shared file has been received so far.
 */
@Immutable
@NotNullByDefault
public class GroupFileStatus {

	private final GroupFileHeader header;
	private final boolean manifestReceived;
	private final int chunksReceived;

	public GroupFileStatus(GroupFileHeader header, boolean manifestReceived,
			int chunksReceived) {
		this.header = header;
		this.manifestReceived = manifestReceived;
		this.chunksReceived = chunksReceived;
	}

	public GroupFileHeader getHeader() {
		return header;
	}

	/**
	 * Returns true if the manifest listing the file's chunks has arrived.
	 * Chunks can arrive before the manifest, but the file can't be read
	 * until the manifest is known.
	 */
	public boolean isManifestReceived() {
		return manifestReceived;
	}

	public int getChunksReceived() {
		return chunksReceived;
	}

	public int getChunkCount() {
		return header.getChunkCount();
	}

	/**
	 * Returns true if the whole file has been received and can be read.
	 */
	public boolean isComplete() {
		return manifestReceived && chunksReceived == header.getChunkCount();
	}
}
