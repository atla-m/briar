package org.briarproject.briar.api.attachment;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.test.BrambleTestCase;
import org.junit.Test;

import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.briar.api.attachment.FileHeader.NAMELESS;
import static org.junit.Assert.assertEquals;

public class FileHeaderTest extends BrambleTestCase {

	private final GroupId groupId = new GroupId(getRandomId());
	private final MessageId manifestId = new MessageId(getRandomId());

	private FileHeader header(String name, String contentType) {
		return new FileHeader(groupId, manifestId, name, contentType, 123);
	}

	@Test
	public void testALineBreakInANameIsRemoved() {
		// Shown on one line, "video.mp4" would hide the ".apk" below it
		assertEquals("video.mp4.apk",
				header("video.mp4 .apk", "video/mp4").getName());
		assertEquals("video.mp4.apk",
				header("video.mp4\n.apk", "video/mp4").getName());
	}

	@Test
	public void testDirectionOverridesAreRemoved() {
		assertEquals("reportfdp.apk",
				header("report‮fdp.apk", "application/pdf").getName());
	}

	@Test
	public void testInvisibleCharactersAreRemoved() {
		// Zero-width space, zero-width joiner, byte order mark, soft
		// hyphen and the direction marks are all invisible
		assertEquals("a.txt", header("​a‍﻿­‎‏.txt",
				"text/plain").getName());
	}

	@Test
	public void testTheZeroWidthNonJoinerIsKept() {
		String persian = "می‌خواهم.txt";
		assertEquals(persian, header(persian, "text/plain").getName());
	}

	@Test
	public void testANameOfNothingButRemovedCharactersGetsAStandIn() {
		assertEquals(NAMELESS, header("​​", "text/plain").getName());
	}

	@Test
	public void testSaveNameTakesItsExtensionFromTheContentType() {
		assertEquals("video.mp4",
				header("video.mp4", "video/mp4").getSaveName("mp4"));
		assertEquals("Video.MP4",
				header("Video.MP4", "video/mp4").getSaveName("mp4"));
		assertEquals("video.mp4",
				header("video.apk", "video/mp4").getSaveName("mp4"));
		assertEquals("video.mp4",
				header("video", "video/mp4").getSaveName("mp4"));
		assertEquals("archive.tar.gz",
				header("archive.tar.gz", "application/gzip")
						.getSaveName("gz"));
	}

	@Test
	public void testSaveNameOfADisguisedFileIsTheTypesOwn() {
		// After the line break is removed the name ends in ".apk", which
		// the content type says it isn't
		assertEquals("video.mp4",
				header("video.mp4 .apk", "video/mp4").getSaveName("mp4"));
	}

	@Test
	public void testSaveNameDropsTheExtensionOfAnUnknownType() {
		// The name's extension can't be checked against anything, so it
		// isn't trusted
		assertEquals("report",
				header("report.apk", "application/x-unknown")
						.getSaveName(null));
		assertEquals("report",
				header("report", "application/x-unknown").getSaveName(null));
	}
}
