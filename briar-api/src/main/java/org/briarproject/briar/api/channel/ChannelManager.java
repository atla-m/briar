package org.briarproject.briar.api.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
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
	MessageId post(GroupId g, String text) throws DbException;

	/**
	 * Posts to a channel we own, carrying images and files as well as
	 * optional text. The headers are covered by the post's signature, so
	 * whoever passes the post on cannot swap or drop them.
	 *
	 * @throws NoSuchChannelException if we do not own this channel
	 */
	MessageId post(GroupId g, @Nullable String text,
			List<AttachmentHeader> attachments, List<FileHeader> files)
			throws DbException;

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
	 * Turns sharing with contacts on or off for a channel we hold. On, the
	 * channel is offered to every contact who can take it, now and as
	 * contacts are added, and a contact's offer of the channel is accepted
	 * without asking, so two contacts who both turn this on exchange the
	 * channel's posts over any connection. Off, nothing is offered and
	 * offers are shown for a decision, as for any blog. Turning it on
	 * tells contacts that we follow the channel.
	 */
	void setSharingWithContacts(GroupId g, boolean on) throws DbException;

	boolean isSharingWithContacts(GroupId g) throws DbException;

	boolean isSharingWithContacts(Transaction txn, GroupId g)
			throws DbException;

	/**
	 * Reads an attachment's file, as a mirror serves it, and stores the
	 * chunks of the given file it carries, bounded by the manifest.
	 *
	 * @return The number of chunks read
	 */
	int importChannelFile(GroupId g, MessageId manifestId, InputStream in)
			throws DbException, IOException, FormatException;

	/**
	 * Returns the channel a link describes, without subscribing to it, so
	 * the person can be shown what they are about to subscribe to.
	 *
	 * @throws FormatException If the link is malformed
	 */
	Blog readLink(String link) throws FormatException;

	/**
	 * Writes a channel's posts to the given stream, in the format its
	 * subscribers can import: the file a mirror serves. The stream carries
	 * the channel's own signed messages, so whoever stores or serves it
	 * cannot alter them.
	 * <p>
	 * It carries the posts and the manifests describing their images and
	 * files, but not the files' chunks, which are published separately,
	 * one file each, by {@link #exportChannelFile}. Otherwise one video
	 * would fill the limit on what a subscriber reads from mirrors over a
	 * channel's whole life, and every subscriber would have to fetch every
	 * file to reach the newest post.
	 */
	void exportChannel(GroupId g, OutputStream out)
			throws DbException, IOException;

	/**
	 * Writes a channel's posts to the given stream, as
	 * {@link #exportChannel(GroupId, OutputStream)} does, and if
	 * {@code withFiles} is true the chunks of every file we hold in full as
	 * well, so that one file handed over carries everything.
	 */
	void exportChannel(GroupId g, OutputStream out, boolean withFiles)
			throws DbException, IOException;

	/**
	 * Returns the manifest IDs of the channel's images and files whose
	 * chunks we hold in full, which can be published with
	 * {@link #exportChannelFile}.
	 */
	Collection<MessageId> getCompleteFiles(GroupId g) throws DbException;

	/**
	 * Writes the chunks of one of a channel's images or files to the given
	 * stream: the attachment file a mirror serves at
	 * {@link #getFilePath(MessageId)} beside the channel's main file.
	 *
	 * @throws NoSuchMessageException If we don't hold every chunk
	 */
	void exportChannelFile(GroupId g, MessageId manifestId, OutputStream out)
			throws DbException, IOException;

	/**
	 * Returns where the attachment file for the given manifest is
	 * published, relative to the channel's main file.
	 */
	String getFilePath(MessageId manifestId);

	/**
	 * Fetches the chunks of one of a channel's images or files from its
	 * mirrors. The manifest must have arrived: it fixes the file's size and
	 * every chunk's hash, so a mirror can send nothing but those chunks.
	 * Small files are fetched after every fetch of the channel; larger ones
	 * when the user asks.
	 */
	FetchResult fetchChannelFile(GroupId g, MessageId manifestId)
			throws DbException;

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

	/**
	 * Reads the header of a channel stream and returns the channel it
	 * carries, without storing anything. The header holds the channel's
	 * title and public key, the same identity a channel link carries, so
	 * a file on its own is enough to subscribe from; this lets the app say
	 * which channel a file holds before asking whether to subscribe.
	 *
	 * @throws FormatException If the stream doesn't start with a valid
	 * channel header
	 */
	Blog readChannelHeader(InputStream in)
			throws IOException, FormatException;

	/**
	 * Reads a channel stream as {@link #importChannel(InputStream)} does,
	 * but if {@code subscribe} is true and we don't hold the channel the
	 * stream carries, subscribes to it first.
	 * <p>
	 * A file must not add channels nobody asked for, so the caller passes
	 * true only after the user has seen which channel the file holds, from
	 * {@link #readChannelHeader(InputStream)}, and agreed. Like subscribing
	 * from a link, this tells no one. The stream carries no mirrors, so a
	 * channel subscribed to this way can be updated by file or by
	 * contacts, and from mirrors only once a link arrives.
	 *
	 * @return the number of messages read from the stream, including any
	 * we already had
	 * @throws NoSuchChannelException If {@code subscribe} is false and we
	 * are not subscribed to the channel the stream belongs to
	 * @throws FormatException If the stream is malformed
	 */
	int importChannel(InputStream in, boolean subscribe)
			throws DbException, IOException, FormatException;
}
