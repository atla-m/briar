package org.briarproject.briar.android.blog;

import android.app.Application;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.sync.event.GroupAddedEvent;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.LiveResult;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.R;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.FetchResult;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.logging.Level.WARNING;
import static org.briarproject.briar.api.channel.ChannelConstants.FILES_DIRECTORY;
import static org.briarproject.briar.api.channel.ChannelConstants.FILE_EXTENSION;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.now;

@NotNullByDefault
class ChannelViewModel extends DbViewModel implements EventListener {

	private static final String OCTET_STREAM = "application/octet-stream";

	private static final Logger LOG =
			getLogger(ChannelViewModel.class.getName());

	private final ChannelManager channelManager;
	private final EventBus eventBus;
	private final Executor dbExecutor;
	private final Executor ioExecutor;

	private final MutableLiveData<LiveResult<List<ChannelItem>>> channels =
			new MutableLiveData<>();
	private final MutableLiveEvent<GroupId> channelCreated =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<GroupId> subscribed =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<String> channelLink =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Integer> message =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<FetchResult> fetched =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<List<String>> mirrors =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<PendingImport> confirmImport =
			new MutableLiveEvent<>();

	@Inject
	ChannelViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor,
			@IoExecutor Executor ioExecutor,
			ChannelManager channelManager, EventBus eventBus) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.dbExecutor = dbExecutor;
		this.ioExecutor = ioExecutor;
		this.channelManager = channelManager;
		this.eventBus = eventBus;
		eventBus.addListener(this);
		loadChannels();
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		// A channel can be subscribed to or removed from somewhere else
		// entirely, such as tapping a forwarded post in a conversation, so
		// the list can't only refresh when this screen does the work
		if (e instanceof GroupAddedEvent || e instanceof GroupRemovedEvent) {
			loadChannels();
		}
	}

	LiveData<LiveResult<List<ChannelItem>>> getChannels() {
		return channels;
	}

	LiveEvent<GroupId> getChannelCreated() {
		return channelCreated;
	}

	private void loadChannels() {
		loadFromDb(this::loadChannels, channels::setValue);
	}

	@DatabaseExecutor
	private List<ChannelItem> loadChannels(Transaction txn)
			throws DbException {
		long start = now();
		List<ChannelItem> loaded = new ArrayList<>();
		for (Channel c : channelManager.getChannels(txn)) {
			loaded.add(ChannelItem.owned(c));
		}
		// Channels we only read are listed too, so their reader can fetch
		// them and see where they are published
		for (Blog b : channelManager.getSubscriptions(txn)) {
			loaded.add(ChannelItem.subscribed(b));
		}
		logDuration(LOG, "Loading channels", start);
		return loaded;
	}

	void createChannel(String title) {
		dbExecutor.execute(() -> {
			try {
				Channel channel = channelManager.createChannel(title);
				loadChannels();
				channelCreated.postEvent(channel.getBlogId());
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveEvent<String> getChannelLink() {
		return channelLink;
	}

	LiveEvent<Integer> getMessage() {
		return message;
	}

	void copyLink(GroupId g) {
		dbExecutor.execute(() -> {
			try {
				channelLink.postEvent(channelManager.getChannelLink(g));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void subscribe(String link) {
		dbExecutor.execute(() -> {
			try {
				Blog blog = channelManager.subscribeFromLink(link);
				// Show it in the list straight away, not only after the
				// screen is next opened
				loadChannels();
				subscribed.postEvent(blog.getId());
			} catch (FormatException e) {
				message.postEvent(R.string.channels_subscribe_error);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveEvent<GroupId> getSubscribed() {
		return subscribed;
	}

	/**
	 * Saves a channel to one file, for handing over. With files, the file
	 * carries every image and file we hold as well, so the receiver gets
	 * everything at once.
	 */
	void publish(GroupId g, Uri uri, boolean withFiles) {
		ioExecutor.execute(() -> {
			try (OutputStream out = getApplication().getContentResolver()
					.openOutputStream(uri)) {
				if (out == null) throw new IOException("Cannot open " + uri);
				channelManager.exportChannel(g, out, withFiles);
				message.postEvent(R.string.channels_published);
			} catch (IOException | DbException e) {
				logException(LOG, WARNING, e);
				message.postEvent(R.string.channels_publish_error);
			}
		});
	}

	/**
	 * Writes what a mirror serves into a folder the user picked: the
	 * channel's file, named after the channel, and a "files" folder with
	 * one file for each image or file we hold in full. The user uploads the
	 * folder's contents to their mirrors. Publishing again into the same
	 * folder replaces the channel's file and adds only the new files, whose
	 * names follow from their content.
	 */
	void publishToFolder(GroupId g, Uri tree, String title) {
		ioExecutor.execute(() -> {
			try {
				ContentResolver resolver =
						getApplication().getContentResolver();
				String rootId = DocumentsContract.getTreeDocumentId(tree);
				Uri main = findOrCreate(resolver, tree, rootId,
						title + FILE_EXTENSION, OCTET_STREAM);
				try (OutputStream out =
						resolver.openOutputStream(main, "wt")) {
					if (out == null) throw new IOException("Cannot open");
					channelManager.exportChannel(g, out);
				}
				Collection<MessageId> files =
						channelManager.getCompleteFiles(g);
				if (!files.isEmpty()) {
					String dirName = FILES_DIRECTORY.substring(0,
							FILES_DIRECTORY.length() - 1);
					Uri dir = findOrCreate(resolver, tree, rootId, dirName,
							Document.MIME_TYPE_DIR);
					String dirId = DocumentsContract.getDocumentId(dir);
					for (MessageId id : files) {
						String name = channelManager.getFilePath(id)
								.substring(FILES_DIRECTORY.length());
						// Named after its content, so one already there
						// is this file
						if (findChild(resolver, tree, dirId, name) != null)
							continue;
						Uri f = DocumentsContract.createDocument(resolver,
								dir, OCTET_STREAM, name);
						if (f == null) throw new IOException("Cannot create");
						try (OutputStream out = resolver.openOutputStream(f)) {
							if (out == null)
								throw new IOException("Cannot open");
							channelManager.exportChannelFile(g, id, out);
						}
					}
				}
				message.postEvent(R.string.channels_published_folder);
			} catch (IOException | DbException | RuntimeException e) {
				logException(LOG, WARNING, e);
				message.postEvent(R.string.channels_publish_error);
			}
		});
	}

	private static Uri findOrCreate(ContentResolver resolver, Uri tree,
			String parentId, String name, String mimeType)
			throws IOException {
		Uri existing = findChild(resolver, tree, parentId, name);
		if (existing != null) return existing;
		Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
				parentId);
		Uri created = DocumentsContract.createDocument(resolver, parent,
				mimeType, name);
		if (created == null) throw new IOException("Cannot create " + name);
		return created;
	}

	@Nullable
	private static Uri findChild(ContentResolver resolver, Uri tree,
			String parentId, String name) {
		Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
				parentId);
		String[] columns = {Document.COLUMN_DOCUMENT_ID,
				Document.COLUMN_DISPLAY_NAME};
		try (Cursor c = resolver.query(children, columns, null, null,
				null)) {
			if (c == null) return null;
			while (c.moveToNext()) {
				if (name.equals(c.getString(1))) {
					return DocumentsContract.buildDocumentUriUsingTree(tree,
							c.getString(0));
				}
			}
		}
		return null;
	}

	/**
	 * Reads a channel file the user picked. If we already hold its channel
	 * the posts are imported straight away; if not, the user is asked
	 * first, since a file must not add channels nobody asked for.
	 */
	void importFile(Uri uri) {
		ioExecutor.execute(() -> {
			Blog blog;
			try (InputStream in = open(uri)) {
				blog = channelManager.readChannelHeader(in);
			} catch (IOException e) {
				logException(LOG, WARNING, e);
				message.postEvent(R.string.channels_import_error);
				return;
			}
			try {
				if (holds(blog.getId())) {
					importFile(uri, false);
				} else {
					confirmImport.postEvent(new PendingImport(uri,
							blog.getName()));
				}
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				message.postEvent(R.string.channels_import_error);
			}
		});
	}

	/**
	 * Subscribes to the channel a file holds and reads its posts, once the
	 * user has seen which channel it is and agreed.
	 */
	void confirmImport(PendingImport pending) {
		ioExecutor.execute(() -> importFile(pending.uri, true));
	}

	// IoExecutor
	private void importFile(Uri uri, boolean subscribe) {
		try (InputStream in = open(uri)) {
			channelManager.importChannel(in, subscribe);
			message.postEvent(R.string.channels_imported);
		} catch (IOException | DbException e) {
			logException(LOG, WARNING, e);
			message.postEvent(R.string.channels_import_error);
		}
	}

	private InputStream open(Uri uri) throws IOException {
		InputStream in =
				getApplication().getContentResolver().openInputStream(uri);
		if (in == null) throw new IOException("Cannot open " + uri);
		return in;
	}

	private boolean holds(GroupId g) throws DbException {
		if (channelManager.getChannel(g) != null) return true;
		for (Blog b : channelManager.getSubscriptions()) {
			if (b.getId().equals(g)) return true;
		}
		return false;
	}

	LiveEvent<PendingImport> getConfirmImport() {
		return confirmImport;
	}

	/**
	 * A channel file whose channel we don't hold yet, waiting for the user
	 * to agree to subscribe. The name is what the file claims; the
	 * channel's identity is its public key.
	 */
	static class PendingImport {

		final Uri uri;
		final String name;

		private PendingImport(Uri uri, String name) {
			this.uri = uri;
			this.name = name;
		}
	}

	LiveEvent<List<String>> getMirrors() {
		return mirrors;
	}

	void loadMirrors(GroupId g) {
		dbExecutor.execute(() -> {
			try {
				mirrors.postEvent(channelManager.getMirrors(g));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void setMirrors(GroupId g, List<String> urls) {
		dbExecutor.execute(() -> {
			try {
				channelManager.setMirrors(g, urls);
				message.postEvent(R.string.channels_mirrors_saved);
			} catch (IllegalArgumentException e) {
				message.postEvent(R.string.channels_mirrors_invalid);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void fetch(GroupId g) {
		ioExecutor.execute(() -> {
			try {
				fetched.postEvent(channelManager.fetchChannel(g));
			} catch (DbException e) {
				message.postEvent(R.string.channels_fetch_error);
			}
		});
	}

	LiveEvent<FetchResult> getFetched() {
		return fetched;
	}

	void unsubscribe(GroupId g) {
		dbExecutor.execute(() -> {
			try {
				channelManager.unsubscribe(g);
				loadChannels();
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void deleteChannel(GroupId g) {
		dbExecutor.execute(() -> {
			try {
				channelManager.deleteChannel(g);
				loadChannels();
			} catch (DbException e) {
				handleException(e);
			}
		});
	}
}
