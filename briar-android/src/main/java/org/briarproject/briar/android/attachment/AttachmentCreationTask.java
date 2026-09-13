package org.briarproject.briar.android.attachment;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.android.attachment.media.ImageCompressor;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.logging.Logger;

import androidx.annotation.Nullable;

import static android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH;
import static java.util.Arrays.asList;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.AndroidUtils.getSupportedImageContentTypes;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.LogUtils.now;
import static org.briarproject.briar.android.attachment.media.ImageCompressor.MIME_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_IMAGE_SIZE;

@NotNullByDefault
class AttachmentCreationTask {

	private static final Logger LOG =
			getLogger(AttachmentCreationTask.class.getName());

	/**
	 * The longest side of an image that has to be compressed to fit into a
	 * chunked attachment. Much larger than for single-message images, as the
	 * size limit is much larger too.
	 */
	private static final int MAX_CHUNKED_IMAGE_DIMENSION = 4096;

	private final AttachmentStore attachmentStore;
	private final ContentResolver contentResolver;
	private final ImageCompressor imageCompressor;
	private final GroupId groupId;
	private final Collection<Uri> uris;
	private final boolean needsSize;
	@Nullable
	private volatile AttachmentCreator attachmentCreator;

	private volatile boolean canceled = false;

	AttachmentCreationTask(AttachmentStore attachmentStore,
			ContentResolver contentResolver,
			AttachmentCreator attachmentCreator,
			ImageCompressor imageCompressor,
			GroupId groupId, Collection<Uri> uris, boolean needsSize) {
		this.attachmentStore = attachmentStore;
		this.contentResolver = contentResolver;
		this.imageCompressor = imageCompressor;
		this.groupId = groupId;
		this.uris = uris;
		this.needsSize = needsSize;
		this.attachmentCreator = attachmentCreator;
	}

	void cancel() {
		canceled = true;
		attachmentCreator = null;
	}

	@IoExecutor
	void storeAttachments() {
		for (Uri uri : uris) processUri(uri);
		AttachmentCreator attachmentCreator = this.attachmentCreator;
		if (!canceled && attachmentCreator != null)
			attachmentCreator.onAttachmentCreationFinished();
		this.attachmentCreator = null;
	}

	@IoExecutor
	private void processUri(Uri uri) {
		if (canceled) return;
		try {
			AttachmentHeader h = storeAttachment(uri);
			AttachmentCreator attachmentCreator = this.attachmentCreator;
			if (attachmentCreator != null) {
				attachmentCreator.onAttachmentHeaderReceived(uri, h, needsSize);
			}
		} catch (DbException | IOException e) {
			logException(LOG, WARNING, e);
			AttachmentCreator attachmentCreator = this.attachmentCreator;
			if (attachmentCreator != null) {
				attachmentCreator.onAttachmentError(uri, e);
			}
			canceled = true;
		}
	}

	/**
	 * Returns the size of the content at the given URI in bytes, or
	 * {@link Long#MAX_VALUE} if the size is unknown, so that an image of
	 * unknown size is compressed rather than risking a failed store.
	 */
	@IoExecutor
	private long getSize(Uri uri) {
		try (AssetFileDescriptor fd =
				contentResolver.openAssetFileDescriptor(uri, "r")) {
			if (fd == null) return Long.MAX_VALUE;
			long length = fd.getLength();
			return length == UNKNOWN_LENGTH ? Long.MAX_VALUE : length;
		} catch (IOException | SecurityException e) {
			logException(LOG, WARNING, e);
			return Long.MAX_VALUE;
		}
	}

	@IoExecutor
	private AttachmentHeader storeAttachment(Uri uri)
			throws IOException, DbException {
		long start = now();
		String contentType = contentResolver.getType(uri);
		if (contentType == null) throw new IOException("null content type");
		if (!asList(getSupportedImageContentTypes()).contains(contentType)) {
			throw new UnsupportedMimeTypeException(contentType, uri);
		}
		InputStream is;
		try {
			is = contentResolver.openInputStream(uri);
			if (is == null) throw new IOException();
		} catch (SecurityException e) {
			throw new IOException(e);
		}
		String storedType = contentType;
		long maxSize = attachmentStore.getMaxAttachmentSize(groupId);
		if (maxSize <= MAX_IMAGE_SIZE) {
			// The store keeps each image in a single message, so compress
			// the image to fit into one
			is = imageCompressor.compressImage(is, contentType);
			storedType = MIME_TYPE;
		} else if (getSize(uri) > maxSize) {
			// The store chunks large images, but this one is over its limit,
			// so compress it just enough to fit. Keep more detail than for
			// single-message images, since there's far more room.
			is = imageCompressor.compressImage(is, contentType, maxSize,
					MAX_CHUNKED_IMAGE_DIMENSION);
			storedType = MIME_TYPE;
		}
		long timestamp = System.currentTimeMillis();
		AttachmentHeader h = attachmentStore.addLocalAttachment(groupId,
				timestamp, storedType, is);
		tryToClose(is, LOG, WARNING);
		logDuration(LOG, "Storing attachment", start);
		return h;
	}

}
