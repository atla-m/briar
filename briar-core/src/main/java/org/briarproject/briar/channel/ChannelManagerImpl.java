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
import org.briarproject.briar.attachment.CountingInputStream;
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
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
import static java.util.concurrent.TimeUnit.SECONDS;
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
import static org.briarproject.briar.channel.ChannelConstants.GROUP_KEY_FETCH_OFFSET;
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
			// After every message already in the channel, so publishing
			// again only adds to the end of the published file
			long timestamp = blogManager.getNextTimestamp(txn, g,
					clock.currentTimeMillis());
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
		FetchState state;
		try {
			state = new FetchState(
					meta.getOptionalString(GROUP_KEY_ETAG),
					meta.getOptionalString(GROUP_KEY_LAST_MODIFIED),
					meta.getLong(GROUP_KEY_FETCH_OFFSET, 0L));
		} catch (FormatException e) {
			throw new DbException(e);
		}
		// Try each mirror until one answers. A mirror can withhold the
		// channel but can't change it, so trying another is always safe.
		for (String mirror : mirrors) {
			try {
				return fetchFrom(g, mirror, state);
			} catch (IOException e) {
				logException(LOG, INFO, e);
			}
		}
		// Not the same as being up to date: we may be missing everything
		// published since we last fetched
		if (LOG.isLoggable(INFO)) LOG.info("No mirror answered for channel");
		return new FetchResult(UNREACHABLE, 0);
	}

	/**
	 * What we know about the channel's published file from the last
	 * fetch: the validators the mirror gave us, and how many bytes of it
	 * we have read and stored.
	 */
	private static class FetchState {

		@Nullable
		private final String etag, lastModified;
		private final long offset;

		private FetchState(@Nullable String etag,
				@Nullable String lastModified, long offset) {
			this.etag = etag;
			this.lastModified = lastModified;
			this.offset = offset;
		}
	}

	private FetchResult fetchFrom(GroupId g, String mirror, FetchState state)
			throws DbException, IOException {
		if (state.offset > 0) {
			try {
				return fetchFrom(g, mirror, state, true);
			} catch (ChangedFileException e) {
				// The file we were reading is not the file being served
				// now, so read it from the beginning
				if (LOG.isLoggable(INFO)) {
					LOG.info("Channel file changed, fetching in full");
				}
			}
		}
		try {
			return fetchFrom(g, mirror, state, false);
		} catch (ChangedFileException e) {
			// Only a fetch that is continuing a file can throw this
			throw new AssertionError(e);
		}
	}

	/**
	 * Thrown when a mirror's answer cannot be read as the continuation of
	 * the file we already have part of.
	 */
	private static class ChangedFileException extends Exception {
	}

	private FetchResult fetchFrom(GroupId g, String mirror, FetchState state,
			boolean resume) throws DbException, IOException,
			ChangedFileException {
		Request.Builder b = new Request.Builder().url(mirror).get();
		// Ask the mirror to send the file only if it has changed
		if (state.etag != null) b.addHeader("If-None-Match", state.etag);
		if (state.lastModified != null) {
			b.addHeader("If-Modified-Since", state.lastModified);
		}
		if (resume) b.addHeader("Range", "bytes=" + state.offset + "-");
		Response response =
				httpClientProvider.get().newCall(b.build()).execute();
		try (ResponseBody body = response.body()) {
			int code = response.code();
			if (code == 304) {
				if (LOG.isLoggable(INFO)) LOG.info("Channel unchanged");
				return new FetchResult(UNCHANGED, 0);
			}
			// The file is no longer than the part we already have
			if (code == 416) {
				if (LOG.isLoggable(INFO)) LOG.info("Channel has nothing new");
				return new FetchResult(UNCHANGED, 0);
			}
			if (!response.isSuccessful() || body == null)
				throw new IOException("Response " + code);
			// A mirror that doesn't do ranges answers 200 with the whole
			// file, which is the first fetch all over again
			boolean ranged = resume && code == 206;
			return importFrom(g, body.byteStream(),
					ranged ? state.offset : 0, !ranged,
					response.header("ETag"),
					lastModifiedIfSettled(response));
		} catch (FormatException e) {
			// The mirror served something that isn't this channel, or
			// isn't the rest of the file we were reading
			if (resume) throw new ChangedFileException();
			throw new IOException(e);
		}
	}

	/**
	 * Returns the date the mirror gave for the file, or null if that date
	 * is too recent to rely on. A date is only given to the second, so a
	 * file changed again within the second it was last changed keeps the
	 * date it already had. A subscriber that remembered such a date would
	 * send it back and be told there was nothing new, and would go on
	 * being told that until the file changed in some later second, so the
	 * posts published in between would not arrive at all if the channel
	 * then fell quiet. Mirrors that give a tag as well are unaffected,
	 * because a tag changes whenever the file does.
	 */
	@Nullable
	private String lastModifiedIfSettled(Response response) {
		String lastModified = response.header("Last-Modified");
		if (lastModified == null) return null;
		Date modified = response.headers().getDate("Last-Modified");
		if (modified == null) return null;
		Date served = response.headers().getDate("Date");
		long now = served == null
				? clock.currentTimeMillis() : served.getTime();
		if (now - modified.getTime() < SECONDS.toMillis(1)) return null;
		return lastModified;
	}

	/**
	 * Imports a channel's messages from a stream, which is either the
	 * whole published file or the part of it we don't have yet, and
	 * records how far we got. A stream that ends early leaves the
	 * messages it did carry stored and the offset pointing just past
	 * them, so the next fetch continues from there rather than starting
	 * again.
	 *
	 * @param offset how many bytes of the file the stream starts after
	 */
	private FetchResult importFrom(GroupId g, InputStream in, long offset,
			boolean expectHeader, @Nullable String etag,
			@Nullable String lastModified)
			throws DbException, IOException, FormatException {
		CountingInputStream counted =
				new CountingInputStream(in, Long.MAX_VALUE);
		Progress p = new Progress();
		try {
			importEntries(g, counted, expectHeader, p);
		} finally {
			// The offset is where the last whole message ended, not how
			// far the stream got, so a fetch that breaks in the middle of
			// a message is continued from a place the next one can parse.
			// The validators are only remembered once the whole file has
			// been read, or the next fetch would be told there is nothing
			// new while we are still missing the end of it.
			storeFetchState(g, offset + p.bytes,
					p.complete ? etag : null,
					p.complete ? lastModified : null);
		}
		return new FetchResult(FETCHED, p.messages);
	}

	/**
	 * How far an import got: the messages stored, and the byte count and
	 * trailing bytes at the end of the last whole message.
	 */
	private static class Progress {

		private int messages = 0;
		private long bytes = 0;
		private boolean complete = false;
	}

	private void storeFetchState(GroupId g, long offset, @Nullable String etag,
			@Nullable String lastModified) throws DbException {
		BdfDictionary meta = new BdfDictionary();
		meta.put(GROUP_KEY_FETCH_OFFSET, offset);
		if (etag != null) meta.put(GROUP_KEY_ETAG, etag);
		if (lastModified != null) {
			meta.put(GROUP_KEY_LAST_MODIFIED, lastModified);
		}
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
				for (MessageId m : getStreamOrder(txn, g)) {
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

	/**
	 * Returns the channel's messages in the order a stream carries them:
	 * oldest first, ties broken by message ID. The database returns
	 * messages in no particular order, so the order is imposed here;
	 * it has to be the same every time, or a reader that has part of a
	 * stream cannot ask a mirror for the rest.
	 * <p/>
	 * Because a channel's messages are timestamped after every message
	 * already in it, a new message sorts after all the others, so
	 * publishing again only appends to the stream.
	 */
	private List<MessageId> getStreamOrder(Transaction txn, GroupId g)
			throws DbException {
		Collection<MessageId> ids = db.getMessageIds(txn, g);
		List<MessageId> sorted = new ArrayList<>(ids);
		Map<MessageId, Long> timestamps = new HashMap<>(ids.size());
		for (MessageId m : ids) {
			// The message is read again when it is written out; keeping
			// only the timestamps here bounds what we hold in memory
			timestamps.put(m, db.getMessage(txn, m).getTimestamp());
		}
		Collections.sort(sorted, (a, b) -> {
			int c = Long.compare(requireNonNull(timestamps.get(a)),
					requireNonNull(timestamps.get(b)));
			return c != 0 ? c : compareIds(a, b);
		});
		return sorted;
	}

	private int compareIds(MessageId a, MessageId b) {
		byte[] x = a.getBytes(), y = b.getBytes();
		for (int i = 0; i < x.length; i++) {
			int c = Integer.compare(x[i] & 0xFF, y[i] & 0xFF);
			if (c != 0) return c;
		}
		return 0;
	}

	@Override
	public int importChannel(InputStream in)
			throws DbException, IOException, FormatException {
		CountingInputStream counted =
				new CountingInputStream(in, Long.MAX_VALUE);
		Progress p = new Progress();
		importEntries(null, counted, true, p);
		return p.messages;
	}

	/**
	 * Reads a channel's messages from a stream and stores them, recording
	 * how far it got as it goes. Each message is stored on its own, so a
	 * stream that ends early leaves the messages it did carry behind.
	 *
	 * @param expected the channel the stream must carry, or null to take
	 * the channel from the stream's own header
	 * @param expectHeader false if the stream starts partway through the
	 * file, after the header
	 */
	private void importEntries(@Nullable GroupId expected,
			CountingInputStream in, boolean expectHeader, Progress p)
			throws DbException, IOException, FormatException {
		BdfReader r = bdfReaderFactory.createReader(in);
		GroupId g;
		if (expectHeader) {
			BdfList header = r.readList();
			checkSize(header, 2);
			if (header.getInt(0) != STREAM_FORMAT_VERSION)
				throw new FormatException();
			BdfList descriptor = clientHelper.toList(header.getRaw(1));
			Blog blog = blogFactory.parseBlog(descriptor);
			if (!blog.isChannel()) throw new FormatException();
			g = blog.getId();
			// A mirror can serve any channel's file; this must be ours
			if (expected != null && !expected.equals(g))
				throw new FormatException();
			p.bytes = in.getBytesRead();
		} else {
			// Without a header the group comes from our own subscription,
			// so the stream cannot say which channel it belongs to. Every
			// message is still checked against that channel's key.
			g = requireNonNull(expected);
		}
		while (!r.eof()) {
			if (++p.messages > MAX_STREAM_MESSAGES)
				throw new FormatException();
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
			// The reader has no bytes in hand between messages, so this
			// is exactly where the message ended
			p.bytes = in.getBytesRead();
		}
		p.complete = true;
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
