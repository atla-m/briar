package org.briarproject.briar.android.attachment.media;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AttachmentCleanerImplTest {

	private static final int[] AVC_SEI = {6};
	private static final int[] HEVC_SEI = {39, 40};

	@Test
	public void testDropsSeiUnitsAndKeepsTheRest() {
		// Three length-prefixed NAL units: SPS (7), SEI (6), slice (5)
		byte[] sample = concat(unit(7, 1, 2, 3), unit(6, 9, 9), unit(5, 4, 5));
		byte[] expected = concat(unit(7, 1, 2, 3), unit(5, 4, 5));
		ByteBuffer b = ByteBuffer.wrap(sample);
		int size = AttachmentCleanerImpl.dropNalUnits(b, sample.length,
				AVC_SEI, false);
		assertEquals(expected.length, size);
		assertArrayEquals(expected, bytes(b, size));
	}

	@Test
	public void testDropsHevcSeiUnits() {
		// HEVC types sit in the top six bits of the first header byte
		byte[] prefix = hevcUnit(39, 1), suffix = hevcUnit(40, 2),
				slice = hevcUnit(1, 3, 4);
		byte[] sample = concat(prefix, slice, suffix);
		ByteBuffer b = ByteBuffer.wrap(sample);
		int size = AttachmentCleanerImpl.dropNalUnits(b, sample.length,
				HEVC_SEI, true);
		assertArrayEquals(slice, bytes(b, size));
	}

	@Test
	public void testLeavesASampleItDoesNotUnderstandAlone() {
		// The length prefix runs past the end of the sample
		byte[] sample = {0, 0, 0, 9, 6, 1, 2};
		ByteBuffer b = ByteBuffer.wrap(sample);
		int size = AttachmentCleanerImpl.dropNalUnits(b, sample.length,
				AVC_SEI, false);
		assertEquals(sample.length, size);
		assertArrayEquals(sample, bytes(b, size));
	}

	@Test
	public void testSampleOfOnlySeiBecomesEmpty() {
		byte[] sample = unit(6, 1, 2, 3);
		ByteBuffer b = ByteBuffer.wrap(sample);
		assertEquals(0, AttachmentCleanerImpl.dropNalUnits(b, sample.length,
				AVC_SEI, false));
	}

	@Test
	public void testDropsSeiUnitsBetweenStartCodes() {
		// As the extractor hands samples out: four-byte and three-byte
		// start codes, with an SEI unit between an SPS and a slice
		byte[] sps = {0, 0, 0, 1, 7, 1, 2};
		byte[] sei = {0, 0, 1, 6, 9, 9, 9};
		byte[] slice = {0, 0, 0, 1, 5, 4, 5, 6};
		byte[] sample = concat(sps, sei, slice);
		ByteBuffer b = ByteBuffer.wrap(sample);
		int size = AttachmentCleanerImpl.dropNalUnits(b, sample.length,
				AVC_SEI, false);
		assertArrayEquals(concat(sps, slice), bytes(b, size));
	}

	@Test
	public void testStartCodedSampleOfOnlySeiBecomesEmpty() {
		byte[] sample = {0, 0, 0, 1, 6, 1, 2, 3};
		ByteBuffer b = ByteBuffer.wrap(sample);
		assertEquals(0, AttachmentCleanerImpl.dropNalUnits(b, sample.length,
				AVC_SEI, false));
	}

	@Test
	public void testBlanksEncoderNameInLeadingAacFillElement() {
		// A fill element with a 14-byte payload, as FFmpeg writes it:
		// 110 (fill), 1110 (count 14), 0000 (type), 5 bits of padding,
		// twelve bytes of name, 7 bits of padding. The frame goes on after.
		byte[] frame = concat(new byte[] {(byte) 0xDC, 0x00},
				"Lavc63.2.100".getBytes(), new byte[] {0x02, 0x54, (byte) 0xAB});
		byte[] expected = concat(new byte[] {(byte) 0xDC, 0x00},
				new byte[12], new byte[] {0x02, 0x54, (byte) 0xAB});
		ByteBuffer b = ByteBuffer.wrap(frame);
		AttachmentCleanerImpl.blankLeadingAacFill(b, frame.length);
		assertArrayEquals(expected, bytes(b, frame.length));
	}

	@Test
	public void testLeavesAnAacFrameWithoutFillElementAlone() {
		// A single channel element (type 0) at the start
		byte[] frame = {0x12, 0x02, 0x40, 0x0B, 0x4C, 0x61};
		byte[] copy = frame.clone();
		ByteBuffer b = ByteBuffer.wrap(frame);
		AttachmentCleanerImpl.blankLeadingAacFill(b, frame.length);
		assertArrayEquals(copy, bytes(b, frame.length));
	}

	private static byte[] unit(int type, int... payload) {
		byte[] u = new byte[4 + 1 + payload.length];
		u[3] = (byte) (1 + payload.length);
		u[4] = (byte) type;
		for (int i = 0; i < payload.length; i++) u[5 + i] = (byte) payload[i];
		return u;
	}

	private static byte[] hevcUnit(int type, int... payload) {
		byte[] u = new byte[4 + 2 + payload.length];
		u[3] = (byte) (2 + payload.length);
		u[4] = (byte) (type << 1);
		u[5] = 1;
		for (int i = 0; i < payload.length; i++) u[6 + i] = (byte) payload[i];
		return u;
	}

	private static byte[] concat(byte[]... parts) {
		int n = 0;
		for (byte[] p : parts) n += p.length;
		byte[] out = new byte[n];
		int pos = 0;
		for (byte[] p : parts) {
			System.arraycopy(p, 0, out, pos, p.length);
			pos += p.length;
		}
		return out;
	}

	private static byte[] bytes(ByteBuffer b, int size) {
		byte[] out = new byte[size];
		for (int i = 0; i < size; i++) out[i] = b.get(i);
		return out;
	}
}
