package org.briarproject.bramble.api.plugin;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.net.SocketFactory;

/**
 * Makes socket factories whose connections Tor keeps apart. Connections
 * made with different isolation keys never share a circuit, so a server
 * reached through two of them can't tell from the circuit that they come
 * from the same client.
 */
@NotNullByDefault
public interface IsolatedSocketFactory {

	/**
	 * Returns a socket factory for the given isolation key.
	 */
	SocketFactory create(String isolationKey);
}
