package org.briarproject.briar.android.blog;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.test.BrambleTestCase;
import org.junit.Test;

import static org.briarproject.bramble.test.TestUtils.getAuthor;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ChannelFingerprintTest extends BrambleTestCase {

	@Test
	public void testTheFingerprintDependsOnTheKeyAlone() {
		// Trying title variants must not change the fingerprint, or an
		// impostor could match one without making a key for each try
		Author a = getAuthor();
		Author renamed = new Author(new AuthorId(getRandomId()),
				a.getFormatVersion(), a.getName() + " ", a.getPublicKey());
		assertEquals(ChannelFingerprint.of(a), ChannelFingerprint.of(renamed));
		assertNotEquals(ChannelFingerprint.of(a),
				ChannelFingerprint.of(getAuthor()));
		assertTrue(ChannelFingerprint.of(a)
				.matches("[0-9a-f]{4}( [0-9a-f]{4}){3}"));
	}
}
