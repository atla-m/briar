package org.briarproject.briar.api.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * How much of a shared file has been received so far.
 */
@Immutable
@NotNullByDefault
public class FileStatus {

	private final FileHeader header;
	private final boolean manifestReceived;
	private final int chunksReceived;
	private final boolean requested;

	public FileStatus(FileHeader header, boolean manifestReceived,
			int chunksReceived, boolean requested) {
		this.header = header;
		this.manifestReceived = manifestReceived;
		this.chunksReceived = chunksReceived;
		this.requested = requested;
	}

	public FileHeader getHeader() {
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
	 * Returns true if we have asked for this file's chunks. A file larger
	 * than
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#MAX_PUSHED_FILE_SIZE}
	 * is held back by whoever sent it until someone asks.
	 */
	public boolean isRequested() {
		return requested;
	}

	/**
	 * Returns true if the file is held back and nobody here has asked for
	 * it yet: its manifest has arrived, no chunk has, and the file is too
	 * large to be sent without being asked for. Someone else asking also
	 * releases it, so this can become false without us doing anything.
	 */
	public boolean isAwaitingRequest() {
		return manifestReceived && chunksReceived == 0 && !requested &&
				header.getSize() > MediaConstants.MAX_PUSHED_FILE_SIZE;
	}

	/**
	 * Returns true if the whole file has been received and can be read.
	 */
	public boolean isComplete() {
		return manifestReceived && chunksReceived == header.getChunkCount();
	}
}
