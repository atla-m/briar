package org.briarproject.briar.android.attachment.media;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Blanks the descriptive fields that Android's MP4 writer puts into a file
 * it has just written: the times in the movie, track and media headers, and
 * the values in the writer's metadata list, which carry the Android version.
 * The sample data, the codec configuration and the layout of the file are
 * left alone, so the file still plays. The sample data is cleaned before it
 * is written, by {@link AttachmentCleanerImpl}.
 * <p>
 * Works on the file in place. Box sizes are never changed.
 */
@NotNullByDefault
class Mp4Scrubber {

	/**
	 * Boxes whose children are themselves boxes.
	 */
	private static final String[] CONTAINERS =
			{"moov", "trak", "mdia", "udta", "ilst"};

	/**
	 * Boxes with a version byte and three flag bytes followed by creation
	 * and modification times.
	 */
	private static final String[] TIMED = {"mvhd", "tkhd", "mdhd"};

	private static final int HEADER_LENGTH = 8;

	static void scrub(RandomAccessFile f) throws IOException {
		scrub(f, 0, f.length(), false);
	}

	/**
	 * Walks the boxes between {@code start} and {@code end}.
	 *
	 * @param inList true if these boxes are the items of a metadata list,
	 * whose children hold the values to blank
	 */
	private static void scrub(RandomAccessFile f, long start, long end,
			boolean inList) throws IOException {
		long pos = start;
		while (pos + HEADER_LENGTH <= end) {
			f.seek(pos);
			long size = readUint32(f);
			String type = readType(f);
			long headerLength = HEADER_LENGTH;
			if (size == 1) {
				// 64-bit size follows the type
				size = f.readLong();
				headerLength += 8;
			} else if (size == 0) {
				// Box extends to the end of the file
				size = end - pos;
			}
			if (size < headerLength || pos + size > end) return;
			long payload = pos + headerLength;
			long payloadEnd = pos + size;
			if (inList) {
				// Each item of the list holds one or more data boxes
				scrubListItem(f, payload, payloadEnd);
			} else if (type.equals("meta")) {
				// The ISO form has a version and flags before the children;
				// the QuickTime form, which Android's writer uses, doesn't
				scrub(f, payload + metaHeaderLength(f, payload, payloadEnd),
						payloadEnd, false);
			} else if (type.equals("ilst")) {
				scrub(f, payload, payloadEnd, true);
			} else if (contains(CONTAINERS, type)) {
				scrub(f, payload, payloadEnd, false);
			} else if (contains(TIMED, type)) {
				scrubTimes(f, payload, payloadEnd);
			}
			pos += size;
		}
	}

	/**
	 * Returns 4 if the meta box at {@code payload} has a version and flags
	 * before its first child, otherwise 0. Either way the first child is a
	 * handler box.
	 */
	private static int metaHeaderLength(RandomAccessFile f, long payload,
			long end) throws IOException {
		if (payload + 12 > end) return 0;
		f.seek(payload + 4);
		if (readType(f).equals("hdlr")) return 0;
		return 4;
	}

	private static void scrubListItem(RandomAccessFile f, long start,
			long end) throws IOException {
		long pos = start;
		while (pos + HEADER_LENGTH <= end) {
			f.seek(pos);
			long size = readUint32(f);
			String type = readType(f);
			if (size < HEADER_LENGTH || pos + size > end) return;
			if (type.equals("data")) {
				// Four bytes of type and four of locale precede the value
				long value = pos + HEADER_LENGTH + 8;
				if (value < pos + size) zero(f, value, pos + size);
			}
			pos += size;
		}
	}

	private static void scrubTimes(RandomAccessFile f, long payload,
			long end) throws IOException {
		if (payload + 4 > end) return;
		f.seek(payload);
		int version = f.readUnsignedByte();
		// Version 0 has two 32-bit times, version 1 two 64-bit times
		int timesLength = version == 0 ? 8 : 16;
		long times = payload + 4;
		if (times + timesLength > end) return;
		zero(f, times, times + timesLength);
	}

	private static void zero(RandomAccessFile f, long start, long end)
			throws IOException {
		byte[] zeros = new byte[(int) Math.min(end - start, 4096)];
		f.seek(start);
		long pos = start;
		while (pos < end) {
			int len = (int) Math.min(zeros.length, end - pos);
			f.write(zeros, 0, len);
			pos += len;
		}
	}

	private static long readUint32(RandomAccessFile f) throws IOException {
		return f.readInt() & 0xFFFFFFFFL;
	}

	private static String readType(RandomAccessFile f) throws IOException {
		byte[] b = new byte[4];
		f.readFully(b);
		return new String(b, "ISO-8859-1");
	}

	private static boolean contains(String[] types, String type) {
		return Arrays.asList(types).contains(type);
	}
}
