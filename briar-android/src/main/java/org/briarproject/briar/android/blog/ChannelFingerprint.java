package org.briarproject.briar.android.blog;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.nullsafety.NotNullByDefault;

import static org.briarproject.bramble.util.StringUtils.toHexString;

/**
 * A channel's name proves nothing: a link or a file can carry any name
 * with any key, and two channels with the same name look identical. The
 * key is the identity, so wherever a channel is named, the first 64 bits
 * of its author ID, a hash of the key, are shown beside it as four groups
 * of hex. Readers can compare it with one they got from someone they
 * trust. Matching 64 bits by making keys is out of reach of an attacker
 * who would otherwise just pick the same name.
 */
@NotNullByDefault
public class ChannelFingerprint {

	private static final int BYTES = 8;

	public static String of(Author author) {
		byte[] id = author.getId().getBytes();
		byte[] prefix = new byte[BYTES];
		System.arraycopy(id, 0, prefix, 0, BYTES);
		String hex = toHexString(prefix).toLowerCase();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < hex.length(); i += 4) {
			if (i > 0) sb.append(' ');
			sb.append(hex, i, i + 4);
		}
		return sb.toString();
	}
}
