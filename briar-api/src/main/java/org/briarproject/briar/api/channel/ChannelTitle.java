package org.briarproject.briar.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.regex.Pattern;

/**
 * The characters a channel's title may hold. A title is shown wherever
 * the channel is named, beside the key fingerprint readers check it by,
 * so it must not be able to look like anything else: no line breaks,
 * which could print a fake key line above the real one, and no invisible
 * characters, which could make two different titles look the same or
 * hide the direction of the text around them. The zero-width non-joiner
 * is the one invisible character allowed, since Persian needs it.
 */
@NotNullByDefault
public class ChannelTitle {

	private static final Pattern DISALLOWED = Pattern.compile(
			"[\\p{Cc}\\p{Zl}\\p{Zp}[\\p{Cf}&&[^‌]]]");

	private ChannelTitle() {
	}

	/**
	 * Returns true if the title holds none of the disallowed characters.
	 */
	public static boolean isAllowed(String title) {
		return !DISALLOWED.matcher(title).find();
	}

	/**
	 * Removes the disallowed characters from a title being chosen.
	 */
	public static String clean(String title) {
		return DISALLOWED.matcher(title).replaceAll("");
	}
}
