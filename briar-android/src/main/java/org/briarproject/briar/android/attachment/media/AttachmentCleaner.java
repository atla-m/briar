package org.briarproject.briar.android.attachment.media;

import android.net.Uri;

import org.briarproject.briar.android.attachment.UnsupportedMimeTypeException;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

/**
 * Makes a copy of a chosen file that carries nothing but the picture, the
 * video or the sound itself: no camera or recorder metadata, no author, no
 * place or time, and no original file name. Everything that is sent as a
 * file goes through here first.
 */
@NotNullByDefault
public interface AttachmentCleaner {

	/**
	 * Cleans the file at the given URI.
	 *
	 * @param contentType the type reported by the content provider
	 * @param maxBytes the largest cleaned file the caller can store
	 * @throws UnsupportedMimeTypeException if the file is not an image, a
	 * video or a sound recording of a kind that can be cleaned
	 * @throws IOException if the file can't be read or cleaned
	 */
	CleanAttachment clean(Uri uri, String contentType, long maxBytes)
			throws IOException;

	/**
	 * A cleaned file. Call {@link #discard()} once it has been stored, or
	 * if storing it failed, so that any temporary copy is deleted.
	 */
	interface CleanAttachment {

		/**
		 * A neutral name that says only what kind of file this is.
		 */
		String getName();

		String getContentType();

		/**
		 * Opens a fresh stream over the cleaned bytes each time it's called.
		 */
		StreamSource getSource();

		void discard();
	}
}
