package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SignaturePrivateKey;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfReaderFactory;
import org.briarproject.bramble.api.data.BdfWriter;
import org.briarproject.bramble.api.data.BdfWriterFactory;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorFactory;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogFactory;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogPost;
import org.briarproject.briar.api.blog.BlogPostFactory;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.NoSuchChannelException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_STREAM_MESSAGES;
import static org.briarproject.briar.api.channel.ChannelConstants.STREAM_FORMAT_VERSION;
import static org.briarproject.bramble.util.StringUtils.truncateUtf8;
import static org.briarproject.briar.channel.ChannelConstants.KEY_CHANNELS;
import static org.briarproject.briar.channel.ChannelConstants.KEY_CHANNEL_AUTHOR;
import static org.briarproject.briar.channel.ChannelConstants.KEY_CHANNEL_CREATED;
import static org.briarproject.briar.channel.ChannelConstants.KEY_CHANNEL_PRIVATE_KEY;

/**
 * Stores the key pairs of the channels we own, in this client's own local
 * group, and posts to them. A channel's posts are ordinary blog posts, so
 * everything else about a channel - sharing, reading, unsubscribing - is
 * handled by the blog client.
 */
@Immutable
@NotNullByDefault
class ChannelManagerImpl implements ChannelManager, OpenDatabaseHook {

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final ContactGroupFactory contactGroupFactory;
	private final AuthorFactory authorFactory;
	private final BlogFactory blogFactory;
	private final BlogManager blogManager;
	private final BlogPostFactory blogPostFactory;
	private final MessageFactory messageFactory;
	private final BdfReaderFactory bdfReaderFactory;
	private final BdfWriterFactory bdfWriterFactory;
	private final Clock clock;

	@Inject
	ChannelManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			ContactGroupFactory contactGroupFactory,
			AuthorFactory authorFactory, BlogFactory blogFactory,
			BlogManager blogManager, BlogPostFactory blogPostFactory,
			MessageFactory messageFactory,
			BdfReaderFactory bdfReaderFactory,
			BdfWriterFactory bdfWriterFactory, Clock clock) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.contactGroupFactory = contactGroupFactory;
		this.authorFactory = authorFactory;
		this.blogFactory = blogFactory;
		this.blogManager = blogManager;
		this.blogPostFactory = blogPostFactory;
		this.messageFactory = messageFactory;
		this.bdfReaderFactory = bdfReaderFactory;
		this.bdfWriterFactory = bdfWriterFactory;
		this.clock = clock;
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		Group g = getLocalGroup();
		if (db.containsGroup(txn, g.getId())) return;
		db.addGroup(txn, g);
		storeChannels(txn, new ArrayList<>(0));
	}

	@Override
	public Channel createChannel(String title) throws DbException {
		String name = truncateUtf8(title, MAX_AUTHOR_NAME_LENGTH);
		if (name.isEmpty()) throw new IllegalArgumentException();
		// The channel gets a key pair of its own, so posts are signed by
		// the channel rather than by the identity that created it
		LocalAuthor localAuthor = authorFactory.createLocalAuthor(name);
		Blog blog = blogFactory.createChannelBlog(localAuthor);
		Channel channel =
				new Channel(blog, localAuthor, clock.currentTimeMillis());
		db.transaction(false, txn -> {
			blogManager.addBlog(txn, blog);
			List<Channel> channels = getChannels(txn);
			channels.add(channel);
			storeChannels(txn, channels);
		});
		return channel;
	}

	@Override
	public List<Channel> getChannels() throws DbException {
		return db.transactionWithResult(true, this::getChannels);
	}

	@Override
	public List<Channel> getChannels(Transaction txn) throws DbException {
		List<Channel> channels = new ArrayList<>();
		try {
			BdfDictionary d = clientHelper.getGroupMetadataAsDictionary(txn,
					getLocalGroup().getId());
			for (Object o : d.getList(KEY_CHANNELS)) {
				if (!(o instanceof BdfDictionary)) throw new FormatException();
				channels.add(parseChannel((BdfDictionary) o));
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return channels;
	}

	@Override
	@Nullable
	public Channel getChannel(GroupId g) throws DbException {
		return db.transactionWithResult(true, txn -> getChannel(txn, g));
	}

	@Override
	@Nullable
	public Channel getChannel(Transaction txn, GroupId g) throws DbException {
		for (Channel c : getChannels(txn)) {
			if (c.getBlogId().equals(g)) return c;
		}
		return null;
	}

	@Override
	public void post(GroupId g, String text) throws DbException {
		db.transaction(false, txn -> {
			Channel channel = getChannel(txn, g);
			if (channel == null) throw new NoSuchChannelException();
			long timestamp = clock.currentTimeMillis();
			BlogPost post;
			try {
				post = blogPostFactory.createBlogPost(g, timestamp, null,
						channel.getLocalAuthor(), text);
			} catch (FormatException | GeneralSecurityException e) {
				throw new DbException(e);
			}
			blogManager.addLocalPost(txn, post);
		});
	}

	@Override
	public void deleteChannel(GroupId g) throws DbException {
		db.transaction(false, txn -> {
			List<Channel> channels = getChannels(txn);
			Channel found = null;
			for (Channel c : channels) {
				if (c.getBlogId().equals(g)) found = c;
			}
			if (found == null) throw new NoSuchChannelException();
			channels.remove(found);
			storeChannels(txn, channels);
			blogManager.removeBlog(txn, found.getBlog());
		});
	}

	@Override
	public Blog subscribe(String title, PublicKey publicKey)
			throws DbException {
		String name = truncateUtf8(title, MAX_AUTHOR_NAME_LENGTH);
		Author author = authorFactory.createAuthor(name, publicKey);
		Blog blog = blogFactory.createChannelBlog(author);
		// Subscribing tells no one: it only means we will accept this
		// channel's posts if we are offered them
		db.transaction(false, txn -> blogManager.addBlog(txn, blog));
		return blog;
	}

	@Override
	public void exportChannel(GroupId g, OutputStream out)
			throws DbException, IOException {
		BdfWriter w = bdfWriterFactory.createWriter(out);
		db.transaction(true, txn -> {
			Blog blog = blogManager.getBlog(txn, g);
			if (!blog.isChannel()) throw new NoSuchChannelException();
			// Header: the format version and the channel's descriptor, so
			// a reader can derive the group and check it is the channel
			// they subscribed to
			try {
				w.writeList(BdfList.of(STREAM_FORMAT_VERSION,
						blog.getGroup().getDescriptor()));
				// Each message as it was signed, so the reader validates
				// it rather than trusting whoever served the stream
				for (MessageId m : db.getMessageIds(txn, g)) {
					Message message = db.getMessage(txn, m);
					w.writeList(BdfList.of(message.getTimestamp(),
							message.getBody()));
				}
			} catch (IOException e) {
				throw new DbException(e);
			}
		});
		w.flush();
	}

	@Override
	public int importChannel(InputStream in)
			throws DbException, IOException, FormatException {
		BdfReader r = bdfReaderFactory.createReader(in);
		BdfList header = r.readList();
		checkSize(header, 2);
		if (header.getInt(0) != STREAM_FORMAT_VERSION)
			throw new FormatException();
		BdfList descriptor = clientHelper.toList(header.getRaw(1));
		Blog blog = blogFactory.parseBlog(descriptor);
		if (!blog.isChannel()) throw new FormatException();
		GroupId g = blog.getId();
		int count = 0;
		while (!r.eof()) {
			if (++count > MAX_STREAM_MESSAGES) throw new FormatException();
			BdfList entry = r.readList();
			checkSize(entry, 2);
			long timestamp = entry.getLong(0);
			byte[] body = entry.getRaw(1);
			// The message ID is a hash of the group, timestamp and body,
			// so a stream can't claim a message it didn't carry
			Message m = messageFactory.createMessage(g, timestamp, body);
			db.transaction(false, txn -> {
				if (!db.containsGroup(txn, g))
					throw new NoSuchChannelException();
				db.importMessage(txn, m);
			});
		}
		return count;
	}

	private void storeChannels(Transaction txn, List<Channel> channels)
			throws DbException {
		BdfList list = new BdfList();
		for (Channel c : channels) list.add(toDictionary(c));
		BdfDictionary gm =
				BdfDictionary.of(new BdfEntry(KEY_CHANNELS, list));
		try {
			clientHelper.mergeGroupMetadata(txn, getLocalGroup().getId(), gm);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private BdfDictionary toDictionary(Channel c) {
		LocalAuthor a = c.getLocalAuthor();
		return BdfDictionary.of(
				new BdfEntry(KEY_CHANNEL_AUTHOR, clientHelper.toList(a)),
				new BdfEntry(KEY_CHANNEL_PRIVATE_KEY,
						a.getPrivateKey().getEncoded()),
				new BdfEntry(KEY_CHANNEL_CREATED, c.getCreated())
		);
	}

	private Channel parseChannel(BdfDictionary d) throws FormatException {
		Author author = clientHelper
				.parseAndValidateAuthor(d.getList(KEY_CHANNEL_AUTHOR));
		PrivateKey privateKey = new SignaturePrivateKey(
				d.getRaw(KEY_CHANNEL_PRIVATE_KEY));
		LocalAuthor localAuthor = new LocalAuthor(author.getId(),
				author.getFormatVersion(), author.getName(),
				author.getPublicKey(), privateKey);
		Blog blog = blogFactory.createChannelBlog(localAuthor);
		return new Channel(blog, localAuthor, d.getLong(KEY_CHANNEL_CREATED));
	}

	private Group getLocalGroup() {
		return contactGroupFactory.createLocalGroup(CLIENT_ID, MAJOR_VERSION);
	}
}
