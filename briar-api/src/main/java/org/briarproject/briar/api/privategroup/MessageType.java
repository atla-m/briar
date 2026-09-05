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
	ATTACHMENT(2),
	/**
	 * Describes a file shared in the group: its name, type, size and the IDs
	 * of the {@link #FILE_CHUNK} messages that hold its bytes, in order. A
	 * {@link #POST} references the manifest by message ID. Manifests aren't
	 * shown as messages themselves.
	 */
	FILE_MANIFEST(3),
	/**
	 * One piece of a file, small enough to fit in a single sync message so
	 * that files can be transferred and forwarded piece by piece over slow
	 * or intermittent transports such as Bluetooth.
	 */
	FILE_CHUNK(4);

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
