package org.briarproject.briar.api.channel;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Shares a channel with anyone nearby who holds its link, over Bluetooth,
 * for a limited time. While sharing is on, the phone serves its copy of
 * the channel to any nearby phone that asks with the channel's key, and
 * looks for nearby copies to read. Anyone nearby with the link can tell
 * the phone has the channel while it is on.
 */
@NotNullByDefault
public interface ChannelNearbyManager {

	/**
	 * How long sharing stays on before turning itself off.
	 */
	long NEARBY_DURATION_MS = 60 * 60 * 1000;

	/**
	 * Turns sharing on for {@link #NEARBY_DURATION_MS}, or off.
	 *
	 * @return False if it could not be turned on because Bluetooth is not
	 * available
	 */
	boolean setSharingNearby(GroupId g, boolean on);

	/**
	 * Returns when sharing turns itself off, in milliseconds since the
	 * epoch, or zero if it is off.
	 */
	long getNearbyExpiry(GroupId g);
}
