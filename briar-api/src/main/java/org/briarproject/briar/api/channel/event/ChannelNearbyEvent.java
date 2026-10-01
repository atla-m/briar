package org.briarproject.briar.api.channel.event;

import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * An event that is broadcast when sharing a channel with anyone nearby is
 * turned on or off, by the user or by its timer.
 */
@Immutable
@NotNullByDefault
public class ChannelNearbyEvent extends Event {

	private final GroupId groupId;
	private final long expiry;

	public ChannelNearbyEvent(GroupId groupId, long expiry) {
		this.groupId = groupId;
		this.expiry = expiry;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	/**
	 * Returns when sharing turns itself off, in milliseconds since the
	 * epoch, or zero if it is off.
	 */
	public long getExpiry() {
		return expiry;
	}
}
