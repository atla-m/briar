package org.briarproject.briar.android.attachment.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.exifinterface.media.ExifInterface;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

import javax.inject.Inject;

import static android.graphics.Bitmap.CompressFormat.JPEG;
import static android.graphics.BitmapFactory.decodeStream;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED;
import static androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_IMAGE_SIZE;

class ImageCompressorImpl implements ImageCompressor {

	private static final Logger LOG =
			getLogger(ImageCompressorImpl.class.getName());

	private static final int MAX_ATTACHMENT_DIMENSION = 1000;

	/**
	 * How far into a JPEG the orientation tag is looked for. The EXIF
	 * segment comes before the image data, so this is more than enough.
	 */
	private static final int EXIF_READ_LIMIT = 1024 * 1024;

	private final ImageSizeCalculator imageSizeCalculator;

	@Inject
	ImageCompressorImpl(ImageSizeCalculator imageSizeCalculator) {
		this.imageSizeCalculator = imageSizeCalculator;
	}

	@Override
	public InputStream compressImage(InputStream is, String contentType)
			throws IOException {
		return compressImage(is, contentType, MAX_IMAGE_SIZE,
				MAX_ATTACHMENT_DIMENSION);
	}

	@Override
	public InputStream compressImage(InputStream is, String contentType,
			long maxBytes, int maxDimension) throws IOException {
		try {
			Bitmap bitmap = createBitmap(is, contentType, maxDimension);
			return compressImage(bitmap, maxBytes);
		} finally {
			tryToClose(is, LOG, WARNING);
		}
	}

	@Override
	public InputStream compressImage(Bitmap bitmap) throws IOException {
		return compressImage(bitmap, MAX_IMAGE_SIZE);
	}

	private InputStream compressImage(Bitmap bitmap, long maxBytes)
			throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int quality = 100; quality >= 0; quality -= 10) {
			if (!bitmap.compress(JPEG, quality, out))
				throw new IOException();
			if (out.size() <= maxBytes) {
				if (LOG.isLoggable(INFO)) {
					LOG.info("Compressed image to "
							+ out.size() + " bytes, quality " + quality);
				}
				return new ByteArrayInputStream(out.toByteArray());
			}
			out.reset();
		}
		throw new IOException();
	}

	private Bitmap createBitmap(InputStream is, String contentType, int maxSize)
			throws IOException {
		is = new BufferedInputStream(is);
		Size size = imageSizeCalculator.getSize(is, contentType);
		if (size.hasError()) throw new IOException();
		// The re-encoded image carries no EXIF, so a rotation recorded
		// there has to be applied to the pixels or the copy comes out on
		// its side
		int orientation = getOrientation(is, contentType);
		if (LOG.isLoggable(INFO))
			LOG.info("Original image size: " + size.getWidth() + "x" +
					size.getHeight());
		int dimension = Math.max(size.getWidth(), size.getHeight());
		int inSampleSize = 1;
		while (dimension > maxSize) {
			inSampleSize *= 2;
			dimension /= 2;
		}
		if (LOG.isLoggable(INFO))
			LOG.info("Scaling attachment by factor of " + inSampleSize);
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = inSampleSize;
		if (contentType.equals("image/png"))
			options.inPreferredConfig = Bitmap.Config.RGB_565;
		Bitmap bitmap = decodeStream(is, null, options);
		if (bitmap == null) throw new IOException();
		return applyOrientation(bitmap, orientation);
	}

	private int getOrientation(InputStream is, String contentType) {
		if (!contentType.equals("image/jpeg")) return ORIENTATION_NORMAL;
		is.mark(EXIF_READ_LIMIT);
		try {
			ExifInterface exif = new ExifInterface(is);
			return exif.getAttributeInt(TAG_ORIENTATION, ORIENTATION_NORMAL);
		} catch (IOException e) {
			logException(LOG, WARNING, e);
			return ORIENTATION_NORMAL;
		} finally {
			try {
				is.reset();
			} catch (IOException e) {
				logException(LOG, WARNING, e);
			}
		}
	}

	private Bitmap applyOrientation(Bitmap bitmap, int orientation) {
		Matrix m = new Matrix();
		switch (orientation) {
			case ORIENTATION_UNDEFINED:
			case ORIENTATION_NORMAL:
				return bitmap;
			case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
				m.setScale(-1, 1);
				break;
			case ExifInterface.ORIENTATION_ROTATE_180:
				m.setRotate(180);
				break;
			case ExifInterface.ORIENTATION_FLIP_VERTICAL:
				m.setScale(1, -1);
				break;
			case ExifInterface.ORIENTATION_TRANSPOSE:
				m.setRotate(90);
				m.postScale(-1, 1);
				break;
			case ExifInterface.ORIENTATION_ROTATE_90:
				m.setRotate(90);
				break;
			case ExifInterface.ORIENTATION_TRANSVERSE:
				m.setRotate(270);
				m.postScale(-1, 1);
				break;
			case ExifInterface.ORIENTATION_ROTATE_270:
				m.setRotate(270);
				break;
			default:
				return bitmap;
		}
		Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
				bitmap.getHeight(), m, true);
		if (rotated != bitmap) bitmap.recycle();
		return rotated;
	}

}
