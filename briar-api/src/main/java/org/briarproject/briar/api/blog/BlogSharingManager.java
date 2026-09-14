package org.briarproject.briar.api.blog;

import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.briar.api.sharing.SharingManager;

public interface BlogSharingManager extends SharingManager<Blog> {

	/**
	 * The unique ID of the blog sharing client.
	 */
	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.blog.sharing");

	/**
	 * The current major version of the blog sharing client.
	 */
	int MAJOR_VERSION = 0;

	/**
	 * The current minor version of the blog sharing client.
	 */
	int MINOR_VERSION = 2;

	/**
	 * The first minor version of this client that can parse a channel's
	 * group descriptor. Earlier clients reject an invitation to a channel,
	 * so channels must not be offered to them.
	 */
	int MIN_CHANNEL_MINOR_VERSION = 2;
}
