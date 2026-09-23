package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.WeakSingletonProvider;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SignaturePrivateKey;
import org.briarproject.bramble.api.crypto.SignaturePublicKey;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfReaderFactory;
import org.briarproject.bramble.api.data.BdfWriter;
import org.briarproject.bramble.api.data.BdfWriterFactory;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorFactory;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.event.TransportActiveEvent;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.bramble.util.Base32;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.event.FileProgressEvent;
import org.briarproject.briar.api.attachment.event.FileRequestedEvent;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogFactory;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogPost;
import org.briarproject.briar.api.blog.BlogPostFactory;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.FetchResult;
import org.briarproject.briar.api.channel.NoSuchChannelException;
import org.briarproject.briar.attachment.CountingInputStream;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.data.BdfDictionary.NULL_VALUE;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.StringUtils.toHexString;
import static org.briarproject.bramble.util.StringUtils.truncateUtf8;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_PUSHED_FILE_SIZE;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_TYPE;
import static org.briarproject.briar.api.blog.MessageType.FILE_CHUNK;
import static org.briarproject.briar.api.blog.MessageType.FILE_MANIFEST;
import static org.briarproject.briar.api.blog.MessageType.FILE_REQUEST;
import static org.briarproject.briar.api.channel.ChannelConstants.FETCH_DELAY_INITIAL;
import static org.briarproject.briar.api.channel.ChannelConstants.FETCH_INTERVAL;
import static org.briarproject.briar.api.channel.ChannelConstants.FILES_DIRECTORY;
import static org.briarproject.briar.api.channel.ChannelConstants.FILE_EXTENSION;
import static org.briarproject.briar.api.channel.ChannelConstants.FILE_STREAM_FORMAT_VERSION;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_FORMAT_VERSION;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_PREFIX;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_REGEX;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_IMPORT_BYTES;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_IMPORT_MESSAGES;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_LINK_BYTES;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_MIRRORS;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_MIRROR_LENGTH;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_STREAM_BYTES;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_STREAM_MESSAGES;
import static org.briarproject.briar.api.channel.ChannelConstants.STREAM_FORMAT_VERSION;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.FETCHED;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.NO_MIRRORS;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.UNCHANGED;
import static org.briarproject.briar.api.channel.FetchResult.Outcome.UNREACHABLE;
import static org.briarproject.briar.attachment.ChunkedFileStore.KEY_FILE_CHUNK_INDEX;
import static org.briarproject.briar.attachment.ChunkedFileStore.KEY_FILE_MANIFEST_ID;
import static org.briarproject.briar.channel.ChannelConstants.GROUP_KEY_ETAG;
import static org.briarproject.briar.channel.ChannelConstants.GROUP_KEY_FETCH_MESSAGES;
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

	/**
	 * The most framing, in bytes, an attachment file may add to each chunk
	 * it carries: the entry's list and timestamp, and the chunk's
	 * descriptor, which leaves this much room in a message body.
	 */
	private static final int MAX_FILE_ENTRY_OVERHEAD = 128;

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
		} else if (e instanceof FileProgressEvent) {
			// A manifest arriving reports no chunks yet. If it belongs to
			// a channel with mirrors and the file is small, fetch it now,
			// so it arrives with its post rather than at the next fetch
			FileProgressEvent p = (FileProgressEvent) e;
			if (p.getChunksReceived() != 0) return;
			ioExecutor.execute(() -> {
				try {
					fetchIfSmall(p.getGroupId(), p.getManifestId());
				} catch (DbException ex) {
					logException(LOG, WARNING, ex);
				}
			});
		} else if (e instanceof FileRequestedEvent) {
			// The user asked for a large file. Contacts are asked by the
			// request itself; a channel with mirrors can also serve it
			FileRequestedEvent f = (FileRequestedEvent) e;
			ioExecutor.execute(() -> {
				try {
					if (getMirrors(f.getGroupId()).isEmpty()) return;
					fetchChannelFile(f.getGroupId(), f.getManifestId());
				} catch (DbException ex) {
					logException(LOG, WARNING, ex);
				}
			});
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
		PublicKey publicKey;
		try {
			publicKey = new SignaturePublicKey(parsed.getRaw(2));
		} catch (IllegalArgumentException e) {
			// A link comes from outside, so a key of the wrong length
			// is a malformed link rather than a bug
			throw new FormatException();
		}
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
					meta.getLong(GROUP_KEY_FETCH_OFFSET, 0L),
					meta.getLong(GROUP_KEY_FETCH_MESSAGES, 0L).intValue());
		} catch (FormatException e) {
			throw new DbException(e);
		}
		// Try each mirror until one answers. A mirror can withhold the
		// channel but can't change it, so trying another is always safe.
		for (String mirror : mirrors) {
			FetchResult result;
			try {
				result = fetchFrom(g, mirror, state);
			} catch (IOException e) {
				logException(LOG, INFO, e);
				continue;
			}
			// The channel's file carries its posts and the manifests of
			// their files; small files are fetched now, each from its own
			// file beside the channel's
			try {
				fetchSmallFiles(g);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
			}
			return result;
		}
		// Not the same as being up to date: we may be missing everything
		// published since we last fetched
		if (LOG.isLoggable(INFO)) LOG.info("No mirror answered for channel");
		return new FetchResult(UNREACHABLE, 0);
	}

	/**
	 * What we know about the channel's published file from the last
	 * fetch: the validators the mirror gave us, and how many bytes and
	 * messages of it we have read and stored. The counts are kept so
	 * that the limits on what a file may cost us apply to the whole
	 * file, not to each fetch that carries part of it.
	 */
	private static class FetchState {

		@Nullable
		private final String etag, lastModified;
		private final long offset;
		private final int messages;

		private FetchState(@Nullable String etag,
				@Nullable String lastModified, long offset, int messages) {
			this.etag = etag;
			this.lastModified = lastModified;
			this.offset = offset;
			this.messages = messages;
		}
	}

	private FetchResult fetchFrom(GroupId g, String mirror, FetchState state)
			throws DbException, IOException {
		if (state.offset > 0) {
			try {
				return fetchFrom(g, mirror, state, true);
			} catch (ChangedFileException e) {
				// The file we were reading is not the file being served
				// now, so read it from the beginning, forgetting what we
				// knew about the old one. We must forget the validators
				// as well as the offset: a mirror that gave the same tag
				// or date for a file we can't continue would otherwise
				// answer that there was nothing new, and we would never
				// read the file it is actually serving.
				if (LOG.isLoggable(INFO)) {
					LOG.info("Channel file changed, fetching in full");
				}
				return fetchFrom(g, mirror, new FetchState(null, null, 0, 0));
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
			// A mirror that has less of the file than we do is not
			// serving the file we were reading: it has been replaced by
			// a shorter one. Read that from the beginning, rather than
			// asking for ever for a range it will never have.
			if (code == 416 && resume) throw new ChangedFileException();
			if (!response.isSuccessful() || body == null)
				throw new IOException("Response " + code);
			// A mirror that doesn't do ranges answers 200 with the whole
			// file, which is the first fetch all over again
			boolean ranged = resume && code == 206;
			return importFrom(g, body.byteStream(),
					ranged ? state.offset : 0,
					ranged ? state.messages : 0, !ranged,
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
	 * @param messages how many of the file's messages we already stored
	 */
	private FetchResult importFrom(GroupId g, InputStream in, long offset,
			int messages, boolean expectHeader, @Nullable String etag,
			@Nullable String lastModified)
			throws DbException, IOException, FormatException {
		Progress p = new Progress(MAX_STREAM_BYTES - offset, messages,
				MAX_STREAM_MESSAGES);
		CountingInputStream counted = countUpTo(in, p);
		try {
			importEntries(g, counted, expectHeader, false, p);
		} finally {
			// The offset is where the last whole message ended, not how
			// far the stream got, so a fetch that breaks in the middle of
			// a message is continued from a place the next one can parse.
			// The validators are only remembered once the whole file has
			// been read, or the next fetch would be told there is nothing
			// new while we are still missing the end of it.
			storeFetchState(g, offset + p.bytes, p.messagesBefore + p.messages,
					p.complete ? etag : null,
					p.complete ? lastModified : null);
		}
		return new FetchResult(FETCHED, p.messages);
	}

	/**
	 * Wraps a stream so that it can be read no further than the budget
	 * the file has left, plus one byte: reading that byte is how a file
	 * too large to accept is told apart from one that fills the budget
	 * exactly.
	 */
	private static CountingInputStream countUpTo(InputStream in,
			Progress p) {
		return new CountingInputStream(in, p.budget + 1);
	}

	/**
	 * How far an import got: the messages stored and the byte count at
	 * the end of the last whole message, along with what earlier fetches
	 * of the same file already spent of the limits it has to keep to.
	 */
	private static class Progress {

		private final long budget;
		private final int messagesBefore;
		private final int maxMessages;

		private int messages = 0;
		private long bytes = 0;
		private boolean complete = false;

		private Progress(long budget, int messagesBefore, int maxMessages) {
			this.budget = budget;
			this.messagesBefore = messagesBefore;
			this.maxMessages = maxMessages;
		}
	}

	private void storeFetchState(GroupId g, long offset, int messages,
			@Nullable String etag, @Nullable String lastModified)
			throws DbException {
		BdfDictionary meta = new BdfDictionary();
		meta.put(GROUP_KEY_FETCH_OFFSET, offset);
		meta.put(GROUP_KEY_FETCH_MESSAGES, (long) messages);
		// Both validators are always written, never left as they were.
		// A tag or date we did not just receive describes some earlier
		// file, and sending it back would have a mirror tell us there
		// was nothing new about a file we have not finished reading.
		meta.put(GROUP_KEY_ETAG, etag == null ? NULL_VALUE : etag);
		meta.put(GROUP_KEY_LAST_MODIFIED,
				lastModified == null ? NULL_VALUE : lastModified);
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
		exportChannel(g, out, false);
	}

	@Override
	public void exportChannel(GroupId g, OutputStream out, boolean withFiles)
			throws DbException, IOException {
		BdfWriter w = bdfWriterFactory.createWriter(out);
		db.transaction(true, txn -> {
			Blog blog = blogManager.getBlog(txn, g);
			if (!blog.isChannel()) throw new NoSuchChannelException();
			Set<MessageId> leftOut = getLeftOut(txn, g, withFiles);
			// Header: the format version and the channel's descriptor, so
			// a reader can derive the group and check it is the channel
			// they subscribed to
			try {
				w.writeList(BdfList.of(STREAM_FORMAT_VERSION,
						blog.getGroup().getDescriptor()));
				// Each message as it was signed, so the reader validates
				// it rather than trusting whoever served the stream
				for (MessageId m : getStreamOrder(txn, g)) {
					if (leftOut.contains(m)) continue;
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
	 * Returns the messages a channel's file leaves out: requests for files,
	 * which pass between readers and aren't the channel's content, and
	 * unless files are wanted, every file's chunks, which are published
	 * one file each. Leaving out the same messages every time keeps the
	 * file growing only at its end, and a copy written by a subscriber
	 * identical to the owner's, even if the subscriber hasn't fetched every
	 * file.
	 */
	private Set<MessageId> getLeftOut(Transaction txn, GroupId g,
			boolean withFiles) throws DbException {
		Set<MessageId> leftOut = new HashSet<>();
		try {
			for (Entry<MessageId, BdfDictionary> e :
					clientHelper.getMessageMetadataAsDictionary(txn, g)
							.entrySet()) {
				Long type = e.getValue().getOptionalLong(KEY_TYPE);
				if (type == null) continue;
				if (type == FILE_REQUEST.getInt() ||
						(!withFiles && type == FILE_CHUNK.getInt())) {
					leftOut.add(e.getKey());
				}
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return leftOut;
	}

	@Override
	public Collection<MessageId> getCompleteFiles(GroupId g)
			throws DbException {
		List<MessageId> complete = new ArrayList<>();
		for (MessageId id : getManifestIds(g)) {
			FileHeader h = blogManager.getFileHeader(g, id);
			if (blogManager.getFileStatus(h).isComplete()) complete.add(id);
		}
		return complete;
	}

	private Collection<MessageId> getManifestIds(GroupId g)
			throws DbException {
		return db.transactionWithResult(true, txn -> {
			try {
				return clientHelper.getMessageIds(txn, g, BdfDictionary.of(
						new BdfEntry(KEY_TYPE, FILE_MANIFEST.getInt())));
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public void exportChannelFile(GroupId g, MessageId manifestId,
			OutputStream out) throws DbException, IOException {
		FileHeader h = blogManager.getFileHeader(g, manifestId);
		BdfWriter w = bdfWriterFactory.createWriter(out);
		db.transaction(true, txn -> {
			List<MessageId> chunks =
					getChunksInOrder(txn, g, manifestId, h.getChunkCount());
			// Header: the format version and the manifest, so a reader can
			// check this is the file they asked for
			try {
				w.writeList(BdfList.of(FILE_STREAM_FORMAT_VERSION,
						manifestId));
				for (MessageId id : chunks) {
					Message m = db.getMessage(txn, id);
					w.writeList(BdfList.of(m.getTimestamp(), m.getBody()));
				}
			} catch (IOException e) {
				throw new DbException(e);
			}
		});
		w.flush();
	}

	/**
	 * Returns a file's chunks in index order, one for each index.
	 *
	 * @throws NoSuchMessageException If a chunk is missing
	 */
	private List<MessageId> getChunksInOrder(Transaction txn, GroupId g,
			MessageId manifestId, int count) throws DbException {
		MessageId[] byIndex = new MessageId[count];
		try {
			BdfDictionary query = BdfDictionary.of(
					new BdfEntry(KEY_TYPE, FILE_CHUNK.getInt()),
					new BdfEntry(KEY_FILE_MANIFEST_ID, manifestId));
			for (Entry<MessageId, BdfDictionary> e : clientHelper
					.getMessageMetadataAsDictionary(txn, g, query)
					.entrySet()) {
				int i = e.getValue().getLong(KEY_FILE_CHUNK_INDEX).intValue();
				if (i >= 0 && i < count && byIndex[i] == null) {
					byIndex[i] = e.getKey();
				}
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		List<MessageId> ordered = new ArrayList<>(count);
		for (MessageId id : byIndex) {
			if (id == null) throw new NoSuchMessageException();
			ordered.add(id);
		}
		return ordered;
	}

	@Override
	public String getFilePath(MessageId manifestId) {
		return FILES_DIRECTORY + toHexString(manifestId.getBytes())
				.toLowerCase(Locale.US) + FILE_EXTENSION;
	}

	@Override
	public FetchResult fetchChannelFile(GroupId g, MessageId manifestId)
			throws DbException {
		List<String> mirrors = getMirrors(g);
		if (mirrors.isEmpty()) return new FetchResult(NO_MIRRORS, 0);
		FileHeader h = blogManager.getFileHeader(g, manifestId);
		if (blogManager.getFileStatus(h).isComplete())
			return new FetchResult(UNCHANGED, 0);
		long manifestTimestamp = db.transactionWithResult(true,
				txn -> db.getMessage(txn, manifestId).getTimestamp());
		String path = getFilePath(manifestId);
		// Try each mirror until one answers, as for the channel itself
		for (String mirror : mirrors) {
			HttpUrl base = HttpUrl.parse(mirror);
			HttpUrl url = base == null ? null : base.resolve(path);
			if (url == null) continue;
			try {
				return fetchFileFrom(g, h, manifestTimestamp, url);
			} catch (IOException e) {
				logException(LOG, INFO, e);
			}
		}
		return new FetchResult(UNREACHABLE, 0);
	}

	private FetchResult fetchFileFrom(GroupId g, FileHeader h,
			long manifestTimestamp, HttpUrl url)
			throws DbException, IOException {
		Request request = new Request.Builder().url(url).get().build();
		Response response =
				httpClientProvider.get().newCall(request).execute();
		try (ResponseBody body = response.body()) {
			if (!response.isSuccessful() || body == null)
				throw new IOException("Response " + response.code());
			// The manifest fixes how much this file can be: its bytes, and a
			// little framing for each chunk. One byte beyond that is how a
			// file too large to accept is told apart from one that is full
			long budget = h.getSize() +
					(h.getChunkCount() + 1L) * MAX_FILE_ENTRY_OVERHEAD;
			CountingInputStream in =
					new CountingInputStream(body.byteStream(), budget + 1);
			int read = importFileEntries(g, h, manifestTimestamp, in);
			if (in.getBytesRead() > budget)
				throw new IOException("Attachment file is too large");
			return new FetchResult(FETCHED, read);
		} catch (FormatException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Reads an attachment file and stores its chunks. Nothing but the chunks
	 * of the file named by the manifest, at the timestamp their sender gave
	 * them, is accepted, and no more of them than the file has, so a
	 * hostile mirror can't make us store anything else. Each chunk is still
	 * checked against the manifest's hash when it is delivered.
	 */
	private int importFileEntries(GroupId g, FileHeader h,
			long manifestTimestamp, InputStream in)
			throws DbException, IOException, FormatException {
		BdfReader r = bdfReaderFactory.createReader(in);
		BdfList header = r.readList();
		checkSize(header, 2);
		if (header.getInt(0) != FILE_STREAM_FORMAT_VERSION)
			throw new FormatException();
		if (!Arrays.equals(header.getRaw(1), h.getManifestId().getBytes()))
			throw new FormatException();
		int read = 0;
		while (!r.eof()) {
			if (read == h.getChunkCount()) throw new FormatException();
			BdfList entry = r.readList();
			checkSize(entry, 2);
			long timestamp = entry.getLong(0);
			byte[] body = entry.getRaw(1);
			checkLength(body, 1, MAX_MESSAGE_BODY_LENGTH);
			// A file's chunks all take the timestamp after its manifest's
			if (timestamp != manifestTimestamp + 1)
				throw new FormatException();
			checkChunkDescriptor(body, h.getManifestId());
			Message m = messageFactory.createMessage(g, timestamp, body);
			db.transaction(false, txn -> {
				if (!db.containsGroup(txn, g))
					throw new NoSuchChannelException();
				db.importMessage(txn, m);
			});
			read++;
		}
		return read;
	}

	/**
	 * Checks that a message body starts with the descriptor of a chunk of
	 * the given file.
	 */
	private void checkChunkDescriptor(byte[] body, MessageId manifestId)
			throws FormatException {
		try {
			BdfReader r = bdfReaderFactory.createReader(
					new ByteArrayInputStream(body));
			BdfList descriptor = r.readList();
			checkSize(descriptor, 3);
			if (descriptor.getInt(0) != FILE_CHUNK.getInt())
				throw new FormatException();
			if (!Arrays.equals(descriptor.getRaw(1), manifestId.getBytes()))
				throw new FormatException();
		} catch (IOException e) {
			throw new FormatException();
		}
	}

	/**
	 * Fetches the channel's small files that haven't arrived. Called after
	 * every fetch of the channel, so small images and files arrive with
	 * the posts; larger ones wait until someone asks, as they do between
	 * contacts.
	 */
	private void fetchSmallFiles(GroupId g) throws DbException {
		for (MessageId id : getManifestIds(g)) fetchIfSmall(g, id);
	}

	private void fetchIfSmall(GroupId g, MessageId manifestId)
			throws DbException {
		if (getMirrors(g).isEmpty()) return;
		FileHeader h = blogManager.getFileHeader(g, manifestId);
		if (h.getSize() > MAX_PUSHED_FILE_SIZE) return;
		if (blogManager.getFileStatus(h).isComplete()) return;
		fetchChannelFile(g, manifestId);
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
			return c != 0 ? c : a.compareTo(b);
		});
		return sorted;
	}

	@Override
	public int importChannel(InputStream in)
			throws DbException, IOException, FormatException {
		return importChannel(in, false);
	}

	@Override
	public int importChannel(InputStream in, boolean subscribe)
			throws DbException, IOException, FormatException {
		// A file opened by hand is counted on its own: the user chose it,
		// and reading it again stores nothing new
		Progress p = new Progress(MAX_IMPORT_BYTES, 0, MAX_IMPORT_MESSAGES);
		CountingInputStream counted = countUpTo(in, p);
		importEntries(null, counted, true, subscribe, p);
		return p.messages;
	}

	@Override
	public Blog readChannelHeader(InputStream in)
			throws IOException, FormatException {
		// Bounded like an import, since the file comes from anyone
		Progress p = new Progress(MAX_IMPORT_BYTES, 0, MAX_IMPORT_MESSAGES);
		BdfReader r = bdfReaderFactory.createReader(countUpTo(in, p));
		return readHeader(r);
	}

	/**
	 * Reads a channel stream's header and returns the channel it names.
	 * The header carries the channel blog's descriptor, which is its
	 * title and public key, so the group ID is derived from it rather than
	 * taken on trust.
	 */
	private Blog readHeader(BdfReader r) throws IOException, FormatException {
		BdfList header = r.readList();
		checkSize(header, 2);
		if (header.getInt(0) != STREAM_FORMAT_VERSION)
			throw new FormatException();
		BdfList descriptor = clientHelper.toList(header.getRaw(1));
		Blog blog = blogFactory.parseBlog(descriptor);
		if (!blog.isChannel()) throw new FormatException();
		return blog;
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
	 * @param subscribe true to subscribe to the stream's channel if we
	 * don't hold it; only with a header, and only after the user agreed
	 */
	private void importEntries(@Nullable GroupId expected,
			CountingInputStream in, boolean expectHeader, boolean subscribe,
			Progress p) throws DbException, IOException, FormatException {
		BdfReader r = bdfReaderFactory.createReader(in);
		GroupId g;
		if (expectHeader) {
			Blog blog = readHeader(r);
			g = blog.getId();
			// A mirror can serve any channel's file; this must be ours
			if (expected != null && !expected.equals(g))
				throw new FormatException();
			if (subscribe) {
				// The blog comes from the header's own descriptor, so it
				// is exactly the channel the stream's posts are checked
				// against, not one rebuilt from a title that could differ
				db.transaction(false, txn -> {
					if (!db.containsGroup(txn, g))
						blogManager.addBlog(txn, blog);
				});
			}
			p.bytes = in.getBytesRead();
		} else {
			// Without a header the group comes from our own subscription,
			// so the stream cannot say which channel it belongs to. Every
			// message is still checked against that channel's key.
			g = requireNonNull(expected);
		}
		while (!r.eof()) {
			// The limits apply to the file, so what earlier fetches of
			// it stored counts against them too. Going over one is not a
			// malformed stream but a file we will not store, so we give
			// up on this mirror rather than reading the file again.
			if (p.messagesBefore + p.messages + 1 > p.maxMessages)
				throw new IOException("Channel has too many messages");
			BdfList entry = r.readList();
			checkSize(entry, 2);
			long timestamp = entry.getLong(0);
			byte[] body = entry.getRaw(1);
			// A stream is served by someone we don't trust, so a message
			// it can't have carried is a malformed stream, not a bug
			if (timestamp < 0) throw new FormatException();
			checkLength(body, 1, MAX_MESSAGE_BODY_LENGTH);
			// The message ID is a hash of the group, timestamp and body,
			// so a stream can't claim a message it didn't carry
			Message m = messageFactory.createMessage(g, timestamp, body);
			db.transaction(false, txn -> {
				if (!db.containsGroup(txn, g))
					throw new NoSuchChannelException();
				db.importMessage(txn, m);
			});
			// Counted once it has been stored, so a fetch that fails
			// part way through an entry doesn't spend the file's budget
			// on a message it never imported
			p.messages++;
			// The reader has no bytes in hand between messages, so this
			// is exactly where the message ended
			p.bytes = in.getBytesRead();
		}
		// The stream was allowed one byte beyond the budget, so having
		// read that byte means the file is larger than we will store
		if (in.getBytesRead() > p.budget)
			throw new IOException("Channel is too large");
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
