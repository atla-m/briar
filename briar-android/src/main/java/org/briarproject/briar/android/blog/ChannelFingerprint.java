package org.briarproject.briar.android.blog;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.briarproject.bramble.util.StringUtils.toHexString;
import static org.briarproject.bramble.util.StringUtils.toUtf8;

/**
 * A channel's name proves nothing: a link or a file can carry any name
 * with any key, and two channels with the same name look identical. The
 * key is the identity, so wherever a channel is named, the first 64 bits
 * of a hash of its public key are shown beside it as four groups of hex.
 * Readers can compare it with one they got from someone they trust.
 * <p>
 * The hash covers the key alone. It used to be the author ID, which also
 * hashes the title, so an impostor with a key of their own could match a
 * channel's fingerprint by trying title variants, which is far cheaper
 * than making a new key for each try.
 */
@NotNullByDefault
public class ChannelFingerprint {

	private static final String LABEL =
			"org.briarproject.briar.channel/FINGERPRINT";
	private static final int BYTES = 8;

	public static String of(Author author) {
		byte[] hash;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(toUtf8(LABEL));
			digest.update(author.getPublicKey().getEncoded());
			hash = digest.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
		byte[] prefix = new byte[BYTES];
		System.arraycopy(hash, 0, prefix, 0, BYTES);
		String hex = toHexString(prefix).toLowerCase();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < hex.length(); i += 4) {
			if (i > 0) sb.append(' ');
			sb.append(hex, i, i + 4);
		}
		return sb.toString();
	}
}
