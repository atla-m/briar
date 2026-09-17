package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SignaturePublicKey;
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
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorFactory;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.event.TransportActiveEvent;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.bramble.api.WeakSingletonProvider;
import org.briarproject.bramble.util.Base32;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogFactory;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogPost;
import org.briarproject.briar.api.blog.BlogPostFactory;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.FetchResult;
import org.briarproject.briar.api.channel.NoSuchChannelException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.briarproject.briar.api.channel.FetchResult.Outcome.FETCHED;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.NO_MIRRORS;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.UNCHANGED;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.UNREACHABLE;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.channel.ChannelConstants.FETCH_DELAY_INITIAL;
import static org.briarproject.briar.api.channel.ChannelConstants.FETCH_INTERVAL;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_FORMAT_VERSION;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_PREFIX;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_REGEX;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_LINK_BYTES;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_MIRRORS;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_MIRROR_LENGTH;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_STREAM_MESSAGES;
import static org.briarproject.briar.api.channel.ChannelConstants.STREAM_FORMAT_VERSION;
import static org.briarproject.bramble.util.StringUtils.truncateUtf8;
import static org.briarproject.briar.channel.ChannelConstants.GROUP_KEY_ETAG;
import static org.briarproject.briar.channel.ChannelConstants.GROUP_KEY_LAST_MODIFIED;
import static org.briarproject.briar.channel.ChannelConstants.GROUP_KEY_MIRRORS;
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
@ThreadSafe
@NotNullByDefault
class ChannelManagerImpl
		implements ChannelManager, OpenDatabaseHook, EventListener {

	private static final Logger LOG =
			getLogger(ChannelManagerImpl.class.getName());

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
	private final TaskScheduler scheduler;
	private final Executor ioExecutor;
	private final WeakSingletonProvider<OkHttpClient> httpClientProvider;
	private final AtomicBoolean fetcherStarted = new AtomicBoolean(false);

	@Inject
	ChannelManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			ContactGroupFactory contactGroupFactory,
			AuthorFactory authorFactory, BlogFactory blogFactory,
			BlogManager blogManager, BlogPostFactory blogPostFactory,
			MessageFactory messageFactory,
			BdfReaderFactory bdfReaderFactory,
			BdfWriterFactory bdfWriterFactory, Clock clock,
			TaskScheduler scheduler, @IoExecutor Executor ioExecutor,
			WeakSingletonProvider<OkHttpClient> httpClientProvider) {
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
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		this.httpClientProvider = httpClientProvider;
	}

	@Override
	public void eventOccurred(Event e) {
		// Channels are fetched over Tor, so there's nothing to do until
		// Tor is running
		if (e instanceof TransportActiveEvent) {
			TransportId t = ((TransportActiveEvent) e).getTransportId();
			if (t.equals(TorConstants.ID)) startFetcher();
		}
	}

	private void startFetcher() {
		if (fetcherStarted.getAndSet(true)) return;
		LOG.info("Tor started, scheduling channel fetcher");
		scheduler.scheduleWithFixedDelay(this::fetchAllChannels, ioExecutor,
				FETCH_DELAY_INITIAL, FETCH_INTERVAL, MINUTES);
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
	public MessageId post(GroupId g, String text) throws DbException {
		return post(g, text, emptyList(), emptyList());
	}

	@Override
	public MessageId post(GroupId g, @Nullable String text,
			List<AttachmentHeader> attachments, List<FileHeader> files)
			throws DbException {
		return db.transactionWithResult(false, txn -> {
			Channel channel = getChannel(txn, g);
			if (channel == null) throw new NoSuchChannelException();
			long timestamp = clock.currentTimeMillis();
			BlogPost post;
			try {
				if (attachments.isEmpty() && files.isEmpty()) {
					post = blogPostFactory.createBlogPost(g, timestamp, null,
							channel.getLocalAuthor(), requireNonNull(text));
				} else {
					post = blogPostFactory.createBlogPost(g, timestamp, null,
							channel.getLocalAuthor(), text, attachments,
							files);
				}
			} catch (FormatException | GeneralSecurityException e) {
				throw new DbException(e);
			}
			blogManager.addLocalPost(txn, post);
			return post.getMessage().getId();
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
	public String getChannelLink(GroupId g) throws DbException {
		Blog blog = db.transactionWithResult(true,
				txn -> blogManager.getBlog(txn, g));
		if (!blog.isChannel()) throw new NoSuchChannelException();
		Author a = blog.getAuthor();
		byte[] raw;
		try {
			// The title is part of the channel's identity, so the link
			// has to carry it as well as the public key
			BdfList mirrors = new BdfList();
			for (String mirror : getMirrors(g)) mirrors.add(mirror);
			raw = clientHelper.toByteArray(BdfList.of(LINK_FORMAT_VERSION,
					a.getName(), a.getPublicKey().getEncoded(), mirrors));
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return LINK_PREFIX + Base32.encode(raw).toLowerCase(Locale.US);
	}

	@Override
	public Blog subscribeFromLink(String link)
			throws DbException, FormatException {
		Matcher matcher = LINK_REGEX.matcher(link);
		if (!matcher.find()) throw new FormatException();
		// Discard the prefix and anything around the link
		byte[] raw = Base32.decode(matcher.group(2), false);
		if (raw.length > MAX_LINK_BYTES) throw new FormatException();
		BdfList parsed = clientHelper.toList(raw);
		checkSize(parsed, 4);
		if (parsed.getInt(0) != LINK_FORMAT_VERSION)
			throw new FormatException();
		String title = parsed.getString(1);
		checkLength(title, 1, MAX_AUTHOR_NAME_LENGTH);
		PublicKey publicKey = new SignaturePublicKey(parsed.getRaw(2));
		List<String> mirrors = parseMirrors(parsed.getList(3));
		Blog blog = subscribe(title, publicKey);
		if (!mirrors.isEmpty()) setMirrors(blog.getId(), mirrors);
		return blog;
	}

	private static List<String> parseMirrors(BdfList list)
			throws FormatException {
		if (list.size() > MAX_MIRRORS) throw new FormatException();
		List<String> mirrors = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			String mirror = list.getString(i);
			checkLength(mirror, 1, MAX_MIRROR_LENGTH);
			mirrors.add(mirror);
		}
		return mirrors;
	}

	@Override
	public void setMirrors(GroupId g, List<String> mirrors)
			throws DbException {
		if (mirrors.size() > MAX_MIRRORS)
			throw new IllegalArgumentException();
		BdfList list = new BdfList();
		for (String mirror : mirrors) {
			if (mirror.isEmpty() || mirror.length() > MAX_MIRROR_LENGTH)
				throw new IllegalArgumentException();
			list.add(mirror);
		}
		BdfDictionary meta =
				BdfDictionary.of(new BdfEntry(GROUP_KEY_MIRRORS, list));
		db.transaction(false, txn -> {
			try {
				clientHelper.mergeGroupMetadata(txn, g, meta);
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public List<String> getMirrors(GroupId g) throws DbException {
		return db.transactionWithResult(true, txn -> getMirrors(txn, g));
	}

	private List<String> getMirrors(Transaction txn, GroupId g)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getGroupMetadataAsDictionary(txn, g);
			BdfList list = meta.getOptionalList(GROUP_KEY_MIRRORS);
			if (list == null) return emptyList();
			return parseMirrors(list);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public List<Blog> getSubscriptions() throws DbException {
		return db.transactionWithResult(true, this::getSubscriptions);
	}

	@Override
	public List<Blog> getSubscriptions(Transaction txn) throws DbException {
		Set<GroupId> owned = new HashSet<>();
		for (Channel c : getChannels(txn)) owned.add(c.getBlogId());
		List<Blog> subscriptions = new ArrayList<>();
		for (Blog b : blogManager.getBlogs(txn)) {
			if (b.isChannel() && !owned.contains(b.getId())) {
				subscriptions.add(b);
			}
		}
		return subscriptions;
	}

	@Override
	public void unsubscribe(GroupId g) throws DbException {
		db.transaction(false, txn -> {
			if (getChannel(txn, g) != null) {
				// We created this one, so it has a key pair to delete too
				throw new IllegalArgumentException();
			}
			blogManager.removeBlog(txn, blogManager.getBlog(txn, g));
		});
	}

	@Override
	public FetchResult fetchChannel(GroupId g) throws DbException {
		List<String> mirrors = getMirrors(g);
		if (mirrors.isEmpty()) return new FetchResult(NO_MIRRORS, 0);
		BdfDictionary meta = db.transactionWithResult(true, txn -> {
			try {
				return clientHelper.getGroupMetadataAsDictionary(txn, g);
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
		String etag, lastModified;
		try {
			etag = meta.getOptionalString(GROUP_KEY_ETAG);
			lastModified = meta.getOptionalString(GROUP_KEY_LAST_MODIFIED);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		// Try each mirror until one answers. A mirror can withhold the
		// channel but can't change it, so trying another is always safe.
		for (String mirror : mirrors) {
			try {
				return fetchFrom(g, mirror, etag, lastModified);
			} catch (IOException e) {
				logException(LOG, INFO, e);
			}
		}
		// Not the same as being up to date: we may be missing everything
		// published since we last fetched
		if (LOG.isLoggable(INFO)) LOG.info("No mirror answered for channel");
		return new FetchResult(UNREACHABLE, 0);
	}

	private FetchResult fetchFrom(GroupId g, String mirror,
			@Nullable String etag, @Nullable String lastModified)
			throws DbException, IOException {
		Request.Builder b = new Request.Builder().url(mirror).get();
		// Ask the mirror to send the file only if it has changed
		if (etag != null) b.addHeader("If-None-Match", etag);
		if (lastModified != null) b.addHeader("If-Modified-Since",
				lastModified);
		Response response =
				httpClientProvider.get().newCall(b.build()).execute();
		try (ResponseBody body = response.body()) {
			if (response.code() == 304) {
				if (LOG.isLoggable(INFO)) LOG.info("Channel unchanged");
				return new FetchResult(UNCHANGED, 0);
			}
			if (!response.isSuccessful() || body == null)
				throw new IOException("Response " + response.code());
			int count = importChannel(body.byteStream());
			storeFetchState(g, response.header("ETag"),
					response.header("Last-Modified"));
			return new FetchResult(FETCHED, count);
		} catch (FormatException e) {
			// The mirror served something that isn't this channel
			throw new IOException(e);
		}
	}

	private void storeFetchState(GroupId g, @Nullable String etag,
			@Nullable String lastModified) throws DbException {
		BdfDictionary meta = new BdfDictionary();
		if (etag != null) meta.put(GROUP_KEY_ETAG, etag);
		if (lastModified != null) {
			meta.put(GROUP_KEY_LAST_MODIFIED, lastModified);
		}
		if (meta.isEmpty()) return;
		db.transaction(false, txn -> {
			try {
				clientHelper.mergeGroupMetadata(txn, g, meta);
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public void fetchAllChannels() {
		try {
			List<GroupId> toFetch = db.transactionWithResult(true, txn -> {
				List<GroupId> ids = new ArrayList<>();
				for (GroupId g : blogManager.getBlogIds(txn)) {
					if (!getMirrors(txn, g).isEmpty()) ids.add(g);
				}
				return ids;
			});
			for (GroupId g : toFetch) {
				try {
					fetchChannel(g);
				} catch (DbException e) {
					logException(LOG, WARNING, e);
				}
			}
		} catch (DbException e) {
			logException(LOG, WARNING, e);
		}
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
