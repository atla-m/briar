package org.briarproject.briar.android.attachment.media;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;

public class Mp4ScrubberTest {

	private static final byte[] VERSION = "14".getBytes(StandardCharsets.UTF_8);
	private static final byte[] SAMPLES = {1, 2, 3, 4, 5, 6, 7, 8};

	@Test
	public void testBlanksTimesAndMetadataValuesButNothingElse()
			throws Exception {
		testScrub(true);
	}

	@Test
	public void testHandlesQuickTimeMetaBoxWithoutVersion() throws Exception {
		// Android's MP4 writer uses this form
		testScrub(false);
	}

	private void testScrub(boolean fullMeta) throws Exception {
		byte[] before = file(0x5F5E1000, VERSION, fullMeta);
		byte[] after = file(0, new byte[VERSION.length], fullMeta);
		File f = Files.createTempFile("scrub", ".mp4").toFile();
		try {
			Files.write(f.toPath(), before);
			try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
				Mp4Scrubber.scrub(raf);
			}
			assertArrayEquals(after, Files.readAllBytes(f.toPath()));
		} finally {
			//noinspection ResultOfMethodCallIgnored
			f.delete();
		}
	}

	@Test
	public void testTruncatedFileIsLeftAlone() throws Exception {
		byte[] before = file(0x5F5E1000, VERSION, false);
		byte[] truncated = new byte[before.length - 20];
		System.arraycopy(before, 0, truncated, 0, truncated.length);
		File f = Files.createTempFile("scrub", ".mp4").toFile();
		try {
			Files.write(f.toPath(), truncated);
			try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
				Mp4Scrubber.scrub(raf);
			}
			// The moov box no longer fits in the file, so the walk stops
			// before it without throwing and nothing is changed
			assertArrayEquals(truncated, Files.readAllBytes(f.toPath()));
		} finally {
			//noinspection ResultOfMethodCallIgnored
			f.delete();
		}
	}

	/**
	 * Builds a minimal MP4 layout: ftyp, moov (mvhd v0, trak (tkhd v1,
	 * mdia (mdhd v0)), meta (hdlr, keys, ilst (item (data)))), mdat.
	 */
	private static byte[] file(int time, byte[] versionValue,
			boolean fullMeta) {
		byte[] mvhd = fullBox("mvhd", 0, int32(time), int32(time),
				int32(1000), int32(0));
		byte[] tkhd = fullBox("tkhd", 1, int64(time), int64(time),
				int32(1), int32(0), int64(0));
		byte[] mdhd = fullBox("mdhd", 0, int32(time), int32(time),
				int32(1000), int32(0), int32(0));
		byte[] mdia = box("mdia", mdhd);
		byte[] trak = box("trak", tkhd, mdia);
		byte[] hdlr = fullBox("hdlr", 0, int32(0), ascii("mdta"), int32(0),
				int32(0), int32(0), new byte[] {0});
		byte[] keys = fullBox("keys", 0, int32(1),
				box("mdta", ascii("com.android.version")));
		byte[] data = box("data", int32(1), int32(0), versionValue);
		byte[] item = box("\u0000\u0000\u0000\u0001", data);
		byte[] ilst = box("ilst", item);
		byte[] meta = fullMeta ? fullBox("meta", 0, hdlr, keys, ilst)
				: box("meta", hdlr, keys, ilst);
		byte[] moov = box("moov", mvhd, trak, meta);
		byte[] ftyp = box("ftyp", ascii("isom"), int32(0));
		byte[] mdat = box("mdat", SAMPLES);
		return concat(ftyp, moov, mdat);
	}

	private static byte[] box(String type, byte[]... children) {
		byte[] body = concat(children);
		return concat(int32(8 + body.length), ascii(type), body);
	}

	private static byte[] fullBox(String type, int version,
			byte[]... children) {
		return box(type, new byte[] {(byte) version, 0, 0, 0},
				concat(children));
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] int32(int i) {
		return new byte[] {(byte) (i >> 24), (byte) (i >> 16), (byte) (i >> 8),
				(byte) i};
	}

	private static byte[] int64(long l) {
		return concat(int32((int) (l >> 32)), int32((int) l));
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}
}
