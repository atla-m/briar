package org.briarproject.briar.api.channel;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Creates and posts to channels: blogs published under a key pair of their
 * own rather than under the user's identity, so that a channel can be named
 * whatever its owner likes, a user can have several, and subscribers learn
 * nothing about who else subscribes.
 */
@NotNullByDefault
public interface ChannelManager {

	/**
	 * The unique ID of the channel client, which stores the key pairs of
	 * the channels we own.
	 */
	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.channel");

	/**
	 * The current major version of the channel client.
	 */
	int MAJOR_VERSION = 0;

	/**
	 * Creates a channel with the given title, along with the key pair that
	 * will sign its posts, and subscribes to it.
	 */
	Channel createChannel(String title) throws DbException;

	/**
	 * Returns the channels we own, oldest first.
	 */
	List<Channel> getChannels() throws DbException;

	List<Channel> getChannels(Transaction txn) throws DbException;

	/**
	 * Returns the channel with the given blog ID, or null if we don't own
	 * a channel with that ID.
	 */
	@Nullable
	Channel getChannel(GroupId g) throws DbException;

	@Nullable
	Channel getChannel(Transaction txn, GroupId g) throws DbException;

	/**
	 * Posts to a channel we own.
	 *
	 * @throws NoSuchChannelException If we don't own a channel with the
	 * given ID
	 */
	void post(GroupId g, String text) throws DbException;

	/**
	 * Deletes a channel we own, including its key pair, and unsubscribes
	 * from it. Subscribers keep the posts they already have; no further
	 * posts can ever be made, as the key pair is gone.
	 */
	void deleteChannel(GroupId g) throws DbException;
}
