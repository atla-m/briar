package org.briarproject.briar.android.attachment.media;

import android.app.Application;
import android.content.ContentResolver;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import org.briarproject.briar.android.attachment.UnsupportedMimeTypeException;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;
import static android.media.MediaExtractor.SAMPLE_FLAG_SYNC;
import static android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.bramble.util.LogUtils.logException;

@NotNullByDefault
class AttachmentCleanerImpl implements AttachmentCleaner {

	private static final Logger LOG =
			getLogger(AttachmentCleanerImpl.class.getName());

	/**
	 * The longest side of a cleaned image. Images are re-encoded from
	 * their pixels, which drops everything the camera wrote into the file.
	 */
	private static final int MAX_IMAGE_DIMENSION = 4096;

	/**
	 * Track types that Android's MP4 writer accepts. Samples of these are
	 * copied into a fresh MP4 without re-encoding, so nothing but the
	 * samples and their codec configuration survives.
	 */
	private static final List<String> MP4_TRACK_TYPES = Arrays.asList(
			"video/avc", "video/hevc", "video/mp4v-es", "video/3gpp",
			"audio/mp4a-latm", "audio/3gpp", "audio/amr-wb");

	/**
	 * The format key for a video track's rotation. The constant is only
	 * public from API 23, but the key is read on every version.
	 */
	private static final String KEY_ROTATION = "rotation-degrees";

	private static final int DEFAULT_SAMPLE_BUFFER_SIZE = 4 * 1024 * 1024;

	/**
	 * The length prefix of each NAL unit in an MP4 sample. Nearly every file
	 * uses four bytes; a sample that doesn't parse that way is left as it is.
	 */
	private static final int NAL_LENGTH_SIZE = 4;

	/**
	 * SEI NAL unit types in H.264 and H.265: supplemental messages that
	 * carry, among other things, the encoder's name and settings.
	 */
	private static final int[] AVC_SEI_TYPES = {6};
	private static final int[] HEVC_SEI_TYPES = {39, 40};

	private final Application app;
	private final ImageCompressor imageCompressor;

	@Inject
	AttachmentCleanerImpl(Application app, ImageCompressor imageCompressor) {
		this.app = app;
		this.imageCompressor = imageCompressor;
	}

	@Override
	public CleanAttachment clean(Uri uri, String contentType, long maxBytes)
			throws IOException {
		if (contentType.startsWith("image/")) {
			return cleanImage(uri, contentType, maxBytes);
		} else if (contentType.startsWith("video/") ||
				contentType.startsWith("audio/")) {
			return cleanMedia(uri, contentType, maxBytes);
		} else {
			throw new UnsupportedMimeTypeException(contentType, uri);
		}
	}

	private CleanAttachment cleanImage(Uri uri, String contentType,
			long maxBytes) throws IOException {
		InputStream in = open(uri);
		// The compressor closes the input stream
		InputStream compressed = imageCompressor.compressImage(in,
				contentType, maxBytes, MAX_IMAGE_DIMENSION);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyAndClose(compressed, out);
		byte[] bytes = out.toByteArray();
		return new BytesAttachment("image.jpg", ImageCompressor.MIME_TYPE,
				bytes);
	}

	private CleanAttachment cleanMedia(Uri uri, String contentType,
			long maxBytes) throws IOException {
		File dir = new File(app.getCacheDir(), "clean");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		File out = File.createTempFile("clean", null, dir);
		boolean hasVideo;
		try {
			hasVideo = remux(uri, contentType, out);
			try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
				Mp4Scrubber.scrub(raf);
			}
			if (out.length() > maxBytes) throw new FileTooBigException();
		} catch (IOException | RuntimeException e) {
			//noinspection ResultOfMethodCallIgnored
			out.delete();
			if (e instanceof IOException) throw (IOException) e;
			// The media framework reports an unreadable file this way
			logException(LOG, WARNING, e);
			throw new UnsupportedMimeTypeException(contentType, uri);
		}
		if (hasVideo) return new FileAttachment("video.mp4", "video/mp4", out);
		return new FileAttachment("audio.m4a", "audio/mp4", out);
	}

	/**
	 * Copies the audio and video samples of the file at {@code uri} into
	 * {@code out} as a fresh MP4, without re-encoding them.
	 *
	 * @return true if the file has a video track
	 * @throws UnsupportedMimeTypeException if the file has a track of a kind
	 * the MP4 writer can't take, or no audio or video track at all
	 */
	private boolean remux(Uri uri, String contentType, File out)
			throws IOException {
		MediaExtractor extractor = new MediaExtractor();
		MediaMuxer muxer = null;
		boolean started = false;
		try {
			extractor.setDataSource(app, uri, null);
			muxer = new MediaMuxer(out.getPath(), MUXER_OUTPUT_MPEG_4);
			int trackCount = extractor.getTrackCount();
			int[] outputTracks = new int[trackCount];
			Arrays.fill(outputTracks, -1);
			// For each track, the NAL unit types to drop from its samples
			int[][] droppedNalTypes = new int[trackCount][];
			boolean[] aacTracks = new boolean[trackCount];
			int bufferSize = DEFAULT_SAMPLE_BUFFER_SIZE;
			boolean hasVideo = false, hasTrack = false;
			for (int i = 0; i < trackCount; i++) {
				MediaFormat format = extractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				if (mime == null) continue;
				if (!MP4_TRACK_TYPES.contains(mime)) {
					if (mime.startsWith("video/") || mime.startsWith("audio/"))
						throw new UnsupportedMimeTypeException(mime, uri);
					// Subtitle and metadata tracks are dropped
					continue;
				}
				if (mime.startsWith("video/")) {
					hasVideo = true;
					// Encoders write their name and settings into the
					// stream as SEI messages, which decoders don't need
					if (mime.equals("video/avc")) {
						droppedNalTypes[i] = AVC_SEI_TYPES;
					} else if (mime.equals("video/hevc")) {
						droppedNalTypes[i] = HEVC_SEI_TYPES;
					}
					if (format.containsKey(KEY_ROTATION)) {
						// Without this the picture plays on its side
						muxer.setOrientationHint(
								format.getInteger(KEY_ROTATION));
					}
				}
				if (mime.equals("audio/mp4a-latm")) aacTracks[i] = true;
				if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
					bufferSize = Math.max(bufferSize,
							format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
				}
				extractor.selectTrack(i);
				outputTracks[i] = muxer.addTrack(format);
				hasTrack = true;
			}
			if (!hasTrack) throw new UnsupportedMimeTypeException(contentType,
					uri);
			muxer.start();
			started = true;
			ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
			BufferInfo info = new BufferInfo();
			while (true) {
				int track = extractor.getSampleTrackIndex();
				if (track < 0) break;
				buffer.clear();
				int size = extractor.readSampleData(buffer, 0);
				if (size < 0) break;
				if (droppedNalTypes[track] != null) {
					size = dropNalUnits(buffer, size, droppedNalTypes[track],
							droppedNalTypes[track] == HEVC_SEI_TYPES);
				} else if (aacTracks[track]) {
					blankLeadingAacFill(buffer, size);
				}
				if (size > 0) {
					info.set(0, size, extractor.getSampleTime(),
							(extractor.getSampleFlags() & SAMPLE_FLAG_SYNC) != 0
									? BUFFER_FLAG_KEY_FRAME : 0);
					muxer.writeSampleData(outputTracks[track], buffer, info);
				}
				if (!extractor.advance()) break;
			}
			return hasVideo;
		} finally {
			extractor.release();
			if (muxer != null) {
				try {
					if (started) muxer.stop();
				} catch (RuntimeException e) {
					logException(LOG, WARNING, e);
				}
				muxer.release();
			}
		}
	}

	/**
	 * Removes the NAL units of the given types from a video sample, in
	 * place, and returns the new sample size. The extractor hands out
	 * samples with start codes before each NAL unit, as a decoder wants
	 * them; a file's own length prefixes are understood too. A sample laid
	 * out in neither way is returned unchanged.
	 */
	static int dropNalUnits(ByteBuffer buffer, int size, int[] types,
			boolean hevc) {
		if (size >= 3 && buffer.get(0) == 0 && buffer.get(1) == 0 &&
				(buffer.get(2) == 1 || (size >= 4 && buffer.get(2) == 0 &&
						buffer.get(3) == 1))) {
			return dropStartCodedNalUnits(buffer, size, types, hevc);
		}
		return dropLengthPrefixedNalUnits(buffer, size, types, hevc);
	}

	private static int dropStartCodedNalUnits(ByteBuffer buffer, int size,
			int[] types, boolean hevc) {
		int out = 0;
		int unitStart = 0;
		while (unitStart < size) {
			// The unit runs from its start code to the next start code
			int headerPos = startCodeEnd(buffer, unitStart, size);
			int next = nextStartCode(buffer, headerPos, size);
			if (headerPos < size) {
				int type = nalType(buffer.get(headerPos) & 0xFF, hevc);
				if (!contains(types, type)) {
					out = copy(buffer, unitStart, next, out);
				}
			}
			unitStart = next;
		}
		return out;
	}

	/**
	 * Returns the position of the first byte after the start code at
	 * {@code pos}, which is one or more zero bytes followed by a one.
	 */
	private static int startCodeEnd(ByteBuffer buffer, int pos, int size) {
		while (pos < size && buffer.get(pos) == 0) pos++;
		return pos < size ? pos + 1 : size;
	}

	/**
	 * Returns the position of the next start code at or after {@code pos},
	 * counting any zero bytes that precede its one, or {@code size} if
	 * there is none.
	 */
	private static int nextStartCode(ByteBuffer buffer, int pos, int size) {
		for (int i = pos; i + 2 < size; i++) {
			if (buffer.get(i) == 0 && buffer.get(i + 1) == 0 &&
					buffer.get(i + 2) == 1) {
				while (i > pos && buffer.get(i - 1) == 0) i--;
				return i;
			}
		}
		return size;
	}

	private static int dropLengthPrefixedNalUnits(ByteBuffer buffer,
			int size, int[] types, boolean hevc) {
		// Check the layout first, so a sample is only changed if the whole
		// of it is understood
		int pos = 0;
		while (pos < size) {
			if (pos + NAL_LENGTH_SIZE > size) return size;
			int length = buffer.getInt(pos);
			if (length < 1 || pos + NAL_LENGTH_SIZE + length > size)
				return size;
			pos += NAL_LENGTH_SIZE + length;
		}
		pos = 0;
		int out = 0;
		while (pos < size) {
			int length = buffer.getInt(pos);
			int type = nalType(buffer.get(pos + NAL_LENGTH_SIZE) & 0xFF, hevc);
			int next = pos + NAL_LENGTH_SIZE + length;
			if (!contains(types, type)) out = copy(buffer, pos, next, out);
			pos = next;
		}
		return out;
	}

	private static int nalType(int header, boolean hevc) {
		return hevc ? (header >> 1) & 0x3F : header & 0x1F;
	}

	/**
	 * Moves the bytes between {@code from} and {@code to} down to
	 * {@code out} and returns the position after them.
	 */
	private static int copy(ByteBuffer buffer, int from, int to, int out) {
		if (out != from) {
			for (int i = from; i < to; i++)
				buffer.put(out + i - from, buffer.get(i));
		}
		return out + to - from;
	}

	/**
	 * Blanks the payload of a fill element at the start of a raw AAC frame.
	 * A fill element carries nothing a decoder needs, and some encoders
	 * write their name and version into one at the start of the first
	 * frame. Its length is kept, so the frame still parses.
	 */
	static void blankLeadingAacFill(ByteBuffer buffer, int size) {
		if (size < 2) return;
		int b0 = buffer.get(0) & 0xFF;
		// Element type is the top three bits: 6 is a fill element
		if (b0 >> 5 != 6) return;
		int count = (b0 >> 1) & 0xF;
		int headerBits = 7;
		if (count == 15) {
			// An escape byte follows: the last bit of b0 and seven of b1
			int b1 = buffer.get(1) & 0xFF;
			int escape = ((b0 & 1) << 7) | (b1 >> 1);
			count += escape - 1;
			headerBits = 15;
		}
		// The payload starts with a four-bit extension type; blank every
		// whole byte after that, up to the end of the payload
		int firstByte = (headerBits + 4 + 7) / 8;
		int endByte = (headerBits + 8 * count) / 8;
		if (endByte > size) endByte = size;
		for (int i = firstByte; i < endByte; i++) buffer.put(i, (byte) 0);
	}

	private static boolean contains(int[] types, int type) {
		for (int t : types) if (t == type) return true;
		return false;
	}

	private InputStream open(Uri uri) throws IOException {
		ContentResolver resolver = app.getContentResolver();
		InputStream in;
		try {
			in = resolver.openInputStream(uri);
		} catch (SecurityException e) {
			throw new IOException(e);
		}
		if (in == null) throw new IOException("Cannot open " + uri);
		return in;
	}

	private static class BytesAttachment implements CleanAttachment {

		private final String name, contentType;
		private final byte[] bytes;

		private BytesAttachment(String name, String contentType,
				byte[] bytes) {
			this.name = name;
			this.contentType = contentType;
			this.bytes = bytes;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getContentType() {
			return contentType;
		}

		@Override
		public StreamSource getSource() {
			return () -> new ByteArrayInputStream(bytes);
		}

		@Override
		public void discard() {
		}
	}

	private static class FileAttachment implements CleanAttachment {

		private final String name, contentType;
		private final File file;

		private FileAttachment(String name, String contentType, File file) {
			this.name = name;
			this.contentType = contentType;
			this.file = file;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getContentType() {
			return contentType;
		}

		@Override
		public StreamSource getSource() {
			return () -> new FileInputStream(file);
		}

		@Override
		public void discard() {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}
	}
}
