package org.briarproject.briar.channel;

import org.briarproject.bramble.test.BrambleTestCase;
import org.junit.Test;

import java.io.ByteArrayInputStream;

import static org.junit.Assert.assertEquals;

public class AllowanceInputStreamTest extends BrambleTestCase {

	@Test
	public void testReadsOnlyWhatIsAllowed() throws Exception {
		AllowanceInputStream in = new AllowanceInputStream(
				new ByteArrayInputStream(new byte[10]));
		assertEquals(-1, in.read());
		in.allow(3);
		assertEquals(3, in.read(new byte[5], 0, 5));
		assertEquals(-1, in.read(new byte[5], 0, 5));
		// A new allowance replaces what was left of the old one
		in.allow(4);
		assertEquals(0, in.read());
		assertEquals(3, in.read(new byte[5], 0, 5));
		in.allow(100);
		assertEquals(3, in.read(new byte[5], 0, 5));
		assertEquals(-1, in.read());
	}
}
