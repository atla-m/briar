package org.briarproject.briar.android.attachment.media;

import android.graphics.Bitmap;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

public interface ImageCompressor {

	/**
	 * The MIME type of compressed images
	 */
	String MIME_TYPE = "image/jpeg";

	/**
	 * Load an image from {@code is}, compress it and return an InputStream
	 * from which the resulting image can be read. The image will be compressed
	 * as a JPEG image such that it fits into a message.
	 *
	 * @param is the stream to read the source image from
	 * @param contentType the mimetype of the source image such as "image/jpeg"
	 * as obtained by {@link android.content.ContentResolver#getType(Uri)}
	 * @return a stream from which the resulting image can be read
	 */
	InputStream compressImage(InputStream is, String contentType)
			throws IOException;

	/**
	 * Load an image from {@code is}, compress it and return an InputStream
	 * from which the resulting image can be read. The image will be scaled
	 * down so that neither dimension exceeds {@code maxDimension} and
	 * compressed as a JPEG image such that it fits into {@code maxBytes}.
	 * This is used for images that are stored in chunks, where the limit is
	 * much larger than a single message.
	 */
	InputStream compressImage(InputStream is, String contentType,
			long maxBytes, int maxDimension) throws IOException;

	/**
	 * Compress an image and return an InputStream from which the resulting
	 * image can be read. The image will be compressed as a JPEG image such that
	 * it fits into a message.
	 *
	 * @param bitmap the source image
	 * @return a stream from which the resulting image can be read
	 */
	InputStream compressImage(Bitmap bitmap) throws IOException;

}
