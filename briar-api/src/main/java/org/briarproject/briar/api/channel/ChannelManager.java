package org.briarproject.briar.api.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

	/**
	 * Subscribes to a channel with the given title and public key, which a
	 * subscriber learns from a channel link. Does nothing if we are already
	 * subscribed.
	 * <p>
	 * Subscribing only means we will accept the channel's posts. It does not
	 * fetch anything and tells no one, so a subscription is not visible to
	 * our contacts.
	 */
	Blog subscribe(String title, PublicKey publicKey) throws DbException;

	/**
	 * Sets the mirrors a channel is published at, which its link will
	 * carry and its subscribers will fetch from. A mirror stores a file it
	 * cannot alter, so a hostile mirror can withhold the channel's posts
	 * but cannot change or forge them.
	 */
	void setMirrors(GroupId g, List<String> mirrors) throws DbException;

	/**
	 * Returns the mirrors a channel is published at, which may be empty.
	 */
	List<String> getMirrors(GroupId g) throws DbException;

	/**
	 * Fetches a channel from its mirrors, trying each in turn until one
	 * answers, and stores any posts it carries that we don't have.
	 */
	FetchResult fetchChannel(GroupId g) throws DbException;

	/**
	 * Fetches every channel that has mirrors. Called on a timer while Tor
	 * is running, and when the user asks.
	 */
	void fetchAllChannels();

	/**
	 * Returns the channels we subscribe to but did not create, so cannot
	 * post to.
	 */
	List<Blog> getSubscriptions() throws DbException;

	List<Blog> getSubscriptions(Transaction txn) throws DbException;

	/**
	 * Unsubscribes from a channel we did not create. The posts we already
	 * have are deleted with it.
	 *
	 * @throws IllegalArgumentException If we created the channel, which is
	 * deleted with {@link #deleteChannel(GroupId)} instead
	 */
	void unsubscribe(GroupId g) throws DbException;

	/**
	 * Returns a link that can be given to anyone, carrying everything
	 * needed to subscribe to the channel with the given ID: its title and
	 * public key. The link does not say who created the channel, and
	 * anyone holding it can subscribe without telling anybody.
	 */
	String getChannelLink(GroupId g) throws DbException;

	/**
	 * Subscribes to the channel named by the given link.
	 *
	 * @throws FormatException If the link is malformed
	 */
	Blog subscribeFromLink(String link) throws DbException, FormatException;

	/**
	 * Writes a channel's posts to the given stream, in the format its
	 * subscribers can import. The stream carries the channel's own signed
	 * messages, so whoever stores or serves it cannot alter them.
	 */
	void exportChannel(GroupId g, OutputStream out)
			throws DbException, IOException;

	/**
	 * Reads a channel stream published by a channel's owner and stores the
	 * messages it contains, which are validated exactly as messages
	 * received from a contact are: a post that is not signed by the channel
	 * is rejected.
	 *
	 * @return the number of messages read from the stream, including any
	 * we already had
	 * @throws NoSuchChannelException If we are not subscribed to the
	 * channel the stream belongs to
	 * @throws FormatException If the stream is malformed
	 */
	int importChannel(InputStream in)
			throws DbException, IOException, FormatException;
}
