package org.briarproject.briar.android.blog;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.briarproject.bramble.api.Cancellable;
import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.event.LifecycleEvent;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.briar.api.blog.event.BlogPostAddedEvent;
import org.briarproject.briar.api.blog.BlogFactory;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.RUNNING;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.StringUtils.fromHexString;
import static org.briarproject.bramble.util.StringUtils.toHexString;
import static org.briarproject.briar.api.channel.ChannelConstants.FILES_DIRECTORY;
import static org.briarproject.briar.api.channel.ChannelConstants.FILE_EXTENSION;

/**
 * Writes a channel the user owns into a folder they picked, and keeps it
 * written: after each post the folder is brought up to date, so a copy
 * tool or an upload running outside Briar can push it to the channel's
 * mirrors without the owner doing anything by hand. Briar itself never
 * uploads, so no credentials for any server live on the phone.
 * <p>
 * The folder holds the channel's main file, named after the channel, a
 * {@code files} directory with one file per complete attachment, named
 * after its content, and {@code files/index.txt} listing those names,
 * so a plain copy script can mirror the folder without parsing the
 * channel. The main file and the index are written under a temporary
 * name and renamed, so nothing ever picks up a half-written file. A burst
 * of posts becomes one export, half a minute after the last.
 */
@ThreadSafe
@Singleton
@NotNullByDefault
public class ChannelFolderPublisher implements EventListener {

	static final String SETTINGS_NAMESPACE = "channel-folders";
	static final String INDEX_NAME = "index.txt";
	private static final String OCTET_STREAM = "application/octet-stream";
	private static final String TEXT_PLAIN = "text/plain";
	private static final long EXPORT_DELAY_SECONDS = 30;
	// The name of an image or file in the files directory
	private static final Pattern FILE_NAME =
			Pattern.compile("[0-9a-f]{64}" + Pattern.quote(FILE_EXTENSION) +
					"(\\.tmp|\\.old)?");

	private static final Logger LOG =
			getLogger(ChannelFolderPublisher.class.getName());

	private final Application app;
	private final ChannelManager channelManager;
	private final BlogFactory blogFactory;
	private final SettingsManager settingsManager;
	private final TaskScheduler scheduler;
	private final Executor ioExecutor;
	private final Map<GroupId, Cancellable> pending = new HashMap<>();
	// One export of a channel at a time, since both write the same files
	private final Map<GroupId, Object> locks = new HashMap<>();

	@Inject
	ChannelFolderPublisher(Application app, ChannelManager channelManager,
			SettingsManager settingsManager, TaskScheduler scheduler,
			@IoExecutor Executor ioExecutor, EventBus eventBus,
			BlogFactory blogFactory) {
		this.app = app;
		this.blogFactory = blogFactory;
		this.channelManager = channelManager;
		this.settingsManager = settingsManager;
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		eventBus.addListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof LifecycleEvent &&
				((LifecycleEvent) e).getLifecycleState() == RUNNING) {
			// A post made in the moments before the app last stopped may
			// not have been written out, so every folder is brought up to
			// date once the app is running again
			ioExecutor.execute(() -> {
				try {
					for (GroupId g : getPublished()) scheduleExport(g);
				} catch (DbException ex) {
					logException(LOG, WARNING, ex);
				}
			});
		} else if (e instanceof BlogPostAddedEvent) {
			BlogPostAddedEvent b = (BlogPostAddedEvent) e;
			// Only our own posts change a folder we publish
			if (b.isLocal()) scheduleExport(b.getGroupId());
		} else if (e instanceof GroupRemovedEvent) {
			GroupRemovedEvent g = (GroupRemovedEvent) e;
			// A deleted channel must not keep its folder setting or the
			// permission to write the folder: Android caps those per app
			if (g.getGroup().getClientId().equals(BlogManager.CLIENT_ID)) {
				GroupId id = g.getGroup().getId();
				synchronized (pending) {
					Cancellable c = pending.remove(id);
					if (c != null) c.cancel();
				}
				// What was published goes too: the folder is often on a
				// memory card, where it would outlive the channel
				Group group = g.getGroup();
				ioExecutor.execute(() -> {
					try {
						wipeAndClear(group);
					} catch (DbException ex) {
						logException(LOG, WARNING, ex);
					}
				});
			}
		}
	}

	/**
	 * Remembers the folder for the channel, keeping the permission to
	 * write it across restarts, and writes the channel into it now.
	 */
	public void setFolder(GroupId g, Uri tree) throws DbException,
			IOException {
		app.getContentResolver().takePersistableUriPermission(tree,
				Intent.FLAG_GRANT_READ_URI_PERMISSION |
						Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		Settings s = new Settings();
		s.put(key(g), tree.toString());
		settingsManager.mergeSettings(s, SETTINGS_NAMESPACE);
		export(g, tree);
	}

	/**
	 * Deletes what a deleted channel published to its folder, and stops
	 * publishing it there. Only the channel's own file and its copies go:
	 * other channel files beside it may be the user's own saved copies,
	 * or another channel's. The images and files directory goes too,
	 * unless another channel publishes to the same folder.
	 */
	private void wipeAndClear(Group group) throws DbException {
		GroupId g = group.getId();
		Uri tree = getFolder(g);
		if (tree == null) return;
		boolean shared = sharesFolder(g, tree);
		String only;
		try {
			only = fileName(blogFactory.parseBlog(group).getAuthor()
					.getName());
		} catch (FormatException e) {
			throw new DbException(e);
		}
		try {
			wipeFolder(app.getContentResolver(), tree, only, !shared);
		} catch (IOException | RuntimeException e) {
			// The folder may be gone, or no longer ours to write
			logException(LOG, WARNING, e);
		}
		clearFolder(g);
	}

	private boolean sharesFolder(GroupId g, Uri tree) throws DbException {
		for (GroupId other : getPublished()) {
			if (!other.equals(g) && tree.equals(getFolder(other))) return true;
		}
		return false;
	}

	private Collection<GroupId> getPublished() throws DbException {
		Settings s = settingsManager.getSettings(SETTINGS_NAMESPACE);
		Collection<GroupId> published = new ArrayList<>();
		for (Map.Entry<String, String> e : s.entrySet()) {
			if (e.getValue().isEmpty()) continue;
			try {
				published.add(new GroupId(fromHexString(e.getKey())));
			} catch (FormatException ex) {
				throw new DbException(ex);
			}
		}
		return published;
	}

	/**
	 * Deletes what Briar wrote into folders it publishes channels to:
	 * every channel file, the copies left while writing, and the files
	 * directory's contents. Called when the account is deleted, including
	 * by the panic button. The folders are found from the app's
	 * permissions, which Android keeps outside the account, so this works
	 * while the account is locked. Only names Briar writes are touched.
	 */
	public static void wipeAllFolders(ContentResolver resolver) {
		for (UriPermission p : resolver.getPersistedUriPermissions()) {
			if (!p.isWritePermission()) continue;
			Uri tree = p.getUri();
			try {
				// Every channel file goes, whoever saved it: this is the
				// panic button, and a copy left behind would show that
				// the phone held the channel
				wipeFolder(resolver, tree, null, true);
			} catch (IOException | RuntimeException e) {
				logException(LOG, WARNING, e);
			}
			try {
				resolver.releasePersistableUriPermission(tree,
						Intent.FLAG_GRANT_READ_URI_PERMISSION |
								Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
			} catch (SecurityException e) {
				// Already gone
			}
		}
	}

	/**
	 * Deletes the channel files at the top of the folder, or only the
	 * named one and its copies, and if asked, the images and files
	 * directory's contents and their index.
	 */
	private static void wipeFolder(ContentResolver resolver, Uri tree,
			@Nullable String only, boolean withFiles) throws IOException {
		String rootId = DocumentsContract.getTreeDocumentId(tree);
		for (Map.Entry<String, Uri> e :
				listChildren(resolver, tree, rootId).entrySet()) {
			String name = e.getKey();
			boolean ours = only == null ? isChannelFileName(name) :
					name.equals(only) || name.equals(only + ".tmp") ||
							name.equals(only + ".old");
			if (ours) DocumentsContract.deleteDocument(resolver, e.getValue());
		}
		if (!withFiles) return;
		String dirName =
				FILES_DIRECTORY.substring(0, FILES_DIRECTORY.length() - 1);
		Uri dir = findChild(resolver, tree, rootId, dirName);
		if (dir == null) return;
		String dirId = DocumentsContract.getDocumentId(dir);
		Map<String, Uri> files = listChildren(resolver, tree, dirId);
		int left = files.size();
		for (Map.Entry<String, Uri> e : files.entrySet()) {
			String name = e.getKey();
			if (FILE_NAME.matcher(name).matches() ||
					name.startsWith(INDEX_NAME)) {
				DocumentsContract.deleteDocument(resolver, e.getValue());
				left--;
			}
		}
		if (left == 0) DocumentsContract.deleteDocument(resolver, dir);
	}

	private static boolean isChannelFileName(String name) {
		return name.endsWith(FILE_EXTENSION) ||
				name.endsWith(FILE_EXTENSION + ".tmp") ||
				name.endsWith(FILE_EXTENSION + ".old");
	}

	private static Map<String, Uri> listChildren(ContentResolver resolver,
			Uri tree, String parentId) {
		Map<String, Uri> children = new HashMap<>();
		Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
				parentId);
		String[] columns = {Document.COLUMN_DOCUMENT_ID,
				Document.COLUMN_DISPLAY_NAME};
		try (Cursor c = resolver.query(uri, columns, null, null, null)) {
			if (c == null) return children;
			while (c.moveToNext()) {
				children.put(c.getString(1),
						DocumentsContract.buildDocumentUriUsingTree(tree,
								c.getString(0)));
			}
		}
		return children;
	}

	/**
	 * Stops publishing the channel to its folder. What was written stays.
	 */
	public void clearFolder(GroupId g) throws DbException {
		Uri tree = getFolder(g);
		if (tree == null) return;
		// Settings can't be removed, so an empty value means none
		Settings s = new Settings();
		s.put(key(g), "");
		settingsManager.mergeSettings(s, SETTINGS_NAMESPACE);
		// Another channel may still publish to the same folder
		if (sharesFolder(g, tree)) return;
		try {
			app.getContentResolver().releasePersistableUriPermission(tree,
					Intent.FLAG_GRANT_READ_URI_PERMISSION |
							Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		} catch (SecurityException e) {
			// Already gone
		}
		LOG.info("Stopped publishing channel to folder");
	}

	@Nullable
	public Uri getFolder(GroupId g) throws DbException {
		String uri = settingsManager.getSettings(SETTINGS_NAMESPACE)
				.get(key(g));
		return uri == null || uri.isEmpty() ? null : Uri.parse(uri);
	}

	/**
	 * Returns the channels that are published to a folder.
	 */
	public Collection<GroupId> getPublished(Transaction txn)
			throws DbException {
		Settings s = settingsManager.getSettings(txn, SETTINGS_NAMESPACE);
		Collection<GroupId> published = new ArrayList<>();
		for (Map.Entry<String, String> e : s.entrySet()) {
			if (e.getValue().isEmpty()) continue;
			try {
				published.add(new GroupId(fromHexString(e.getKey())));
			} catch (FormatException ex) {
				throw new DbException(ex);
			}
		}
		return published;
	}

	private void scheduleExport(GroupId g) {
		synchronized (pending) {
			Cancellable c = pending.remove(g);
			if (c != null) c.cancel();
			pending.put(g, scheduler.schedule(() -> exportIfPublished(g),
					ioExecutor, EXPORT_DELAY_SECONDS, SECONDS));
		}
	}

	// IoExecutor
	private void exportIfPublished(GroupId g) {
		synchronized (pending) {
			pending.remove(g);
		}
		try {
			Uri tree = getFolder(g);
			if (tree == null) return;
			export(g, tree);
			if (LOG.isLoggable(INFO)) LOG.info("Published channel to folder");
		} catch (DbException | IOException | RuntimeException e) {
			logException(LOG, WARNING, e);
		}
	}

	/**
	 * Writes the channel, its complete files and the index into the
	 * folder. Files already there are left alone, since they are named
	 * after their content.
	 */
	public void export(GroupId g, Uri tree) throws DbException, IOException {
		synchronized (lockFor(g)) {
			exportLocked(g, tree);
		}
	}

	private Object lockFor(GroupId g) {
		synchronized (locks) {
			Object lock = locks.get(g);
			if (lock == null) {
				lock = new Object();
				locks.put(g, lock);
			}
			return lock;
		}
	}

	private void exportLocked(GroupId g, Uri tree)
			throws DbException, IOException {
		String title = channelManager.getChannel(g).getTitle();
		ContentResolver resolver = app.getContentResolver();
		String rootId = DocumentsContract.getTreeDocumentId(tree);
		String mainName = fileName(title);
		writeViaTemp(resolver, tree, rootId, mainName, OCTET_STREAM,
				out -> channelManager.exportChannel(g, out));
		Collection<MessageId> files = channelManager.getCompleteFiles(g);
		if (files.isEmpty()) return;
		String dirName =
				FILES_DIRECTORY.substring(0, FILES_DIRECTORY.length() - 1);
		Uri dir = findOrCreate(resolver, tree, rootId, dirName,
				Document.MIME_TYPE_DIR);
		String dirId = DocumentsContract.getDocumentId(dir);
		StringBuilder index = new StringBuilder();
		for (MessageId id : files) {
			String name = channelManager.getFilePath(id)
					.substring(FILES_DIRECTORY.length());
			index.append(name).append('\n');
			// A file is only given its name once it has been written
			// whole, so one under its name is complete. A write that was
			// cut off leaves a temporary file, which is written again.
			if (findChild(resolver, tree, dirId, name) != null) continue;
			writeViaTemp(resolver, tree, dirId, name, OCTET_STREAM,
					out -> channelManager.exportChannelFile(g, id, out));
		}
		byte[] indexBytes = index.toString().getBytes(UTF_8);
		writeViaTemp(resolver, tree, dirId, INDEX_NAME, TEXT_PLAIN,
				out -> out.write(indexBytes));
	}

	/**
	 * Writes under a temporary name and swaps it for the old file, so a
	 * copy tool or an upload never picks up a half-written file. The old
	 * file is moved aside rather than deleted until the new one is in
	 * place, so a failure leaves the last good one, and it is moved back
	 * if the swap fails.
	 */
	private static void writeViaTemp(ContentResolver resolver, Uri tree,
			String parentId, String name, String mimeType, Writer writer)
			throws IOException, DbException {
		Uri tmp = findOrCreate(resolver, tree, parentId, name + ".tmp",
				mimeType);
		try (OutputStream out = resolver.openOutputStream(tmp, "wt")) {
			if (out == null) throw new IOException("Cannot open");
			writer.write(out);
		}
		Uri old = findChild(resolver, tree, parentId, name);
		Uri aside = null;
		if (old != null) {
			Uri stale = findChild(resolver, tree, parentId, name + ".old");
			if (stale != null) DocumentsContract.deleteDocument(resolver, stale);
			aside = DocumentsContract.renameDocument(resolver, old,
					name + ".old");
			if (aside == null) throw new IOException("Cannot move " + name);
		}
		if (DocumentsContract.renameDocument(resolver, tmp, name) == null) {
			if (aside != null) {
				DocumentsContract.renameDocument(resolver, aside, name);
			}
			throw new IOException("Cannot rename " + name);
		}
		if (aside != null) DocumentsContract.deleteDocument(resolver, aside);
	}

	private interface Writer {
		void write(OutputStream out) throws IOException, DbException;
	}

	static String fileName(String title) {
		String safe = title.replaceAll("[\\p{Cc}\\p{Cf}/\\\\:*?\"<>|]", "_")
				.trim();
		if (safe.isEmpty()) safe = "channel";
		return safe + FILE_EXTENSION;
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

	private static String key(GroupId g) {
		return toHexString(g.getBytes());
	}
}
