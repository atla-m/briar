package org.briarproject.briar.api.privategroup;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public enum MessageType {

	JOIN(0),
	POST(1),
	/**
	 * An image attachment referenced by a {@link #POST}. Attachments are
	 * not shown as messages themselves; they're loaded via the attachment
	 * headers of the post that references them.
	 */
	ATTACHMENT(2);

	private final int value;

	MessageType(int value) {
		this.value = value;
	}

	public static MessageType valueOf(int value) {
		for (MessageType m : values()) if (m.value == value) return m;
		throw new IllegalArgumentException();
	}

	public int getInt() {
		return value;
	}
}
