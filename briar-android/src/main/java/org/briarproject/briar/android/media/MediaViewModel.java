package org.briarproject.briar.android.media;

import android.app.Application;
import android.media.MediaDataSource;
import android.net.Uri;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.RequiresApi;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.briarproject.briar.android.media.MediaActivity.CLIENT_MESSAGING;
import static org.briarproject.briar.android.media.MediaActivity.CLIENT_BLOG;
import static android.os.Build.VERSION.SDK_INT;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.api.attachment.MediaConstants.FILE_CHUNK_PAYLOAD_LENGTH;

/**
 * Plays and saves a file shared in a private group or a private
 * conversation, reading it straight from its chunks in the database.
 */
@NotNullByDefault
public class MediaViewModel extends DbViewModel {

	private static final Logger LOG =
			getLogger(MediaViewModel.class.getName());

	/**
	 * Where the player reads the file from: a data source backed by the
	 * chunks in the database, or on old Android versions a temporary
	 * decrypted copy.
	 */
	static class Source {
		@Nullable
		final MediaDataSource dataSource;
		@Nullable
		final File file;

		Source(@Nullable MediaDataSource dataSource, @Nullable File file) {
			this.dataSource = dataSource;
			this.file = file;
		}
	}

	private interface FileReader {
		byte[] getFileChunk(FileHeader header, int index) throws DbException;

		InputStream getFile(FileHeader header) throws DbException;
	}

	private final PrivateGroupManager privateGroupManager;
	private final MessagingManager messagingManager;
	private final BlogManager blogManager;
	@IoExecutor
	private final Executor ioExecutor;

	private final MutableLiveData<Source> source = new MutableLiveData<>();
	// true if there was an error, false if the file was saved
	private final MutableLiveEvent<Boolean> saveError =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> loadError =
			new MutableLiveEvent<>();

	@Nullable
	private FileHeader header = null;
	@Nullable
	private FileReader reader = null;
	@Nullable
	private volatile File tempFile = null;

	@Inject
	MediaViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			@IoExecutor Executor ioExecutor,
			PrivateGroupManager privateGroupManager,
			MessagingManager messagingManager,
			BlogManager blogManager) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.ioExecutor = ioExecutor;
		this.privateGroupManager = privateGroupManager;
		this.messagingManager = messagingManager;
		this.blogManager = blogManager;
	}

	/**
	 * Sets the file to play. The file must have been fully received.
	 *
	 * @param client which client holds the file, one of the constants in
	 * {@link MediaActivity}
	 */
	void setFile(FileHeader header, int client) {
		if (this.header != null) return; // Already set
		this.header = header;
		if (client == CLIENT_MESSAGING) {
			reader = new FileReader() {
				@Override
				public byte[] getFileChunk(FileHeader h, int index)
						throws DbException {
					return messagingManager.getFileChunk(h, index);
				}

				@Override
				public InputStream getFile(FileHeader h) throws DbException {
					return messagingManager.getFile(h);
				}
			};
		} else if (client == CLIENT_BLOG) {
			reader = new FileReader() {
				@Override
				public byte[] getFileChunk(FileHeader h, int index)
						throws DbException {
					return blogManager.getFileChunk(h, index);
				}

				@Override
				public InputStream getFile(FileHeader h) throws DbException {
					return blogManager.getFile(h);
				}
			};
		} else {
			reader = new FileReader() {
				@Override
				public byte[] getFileChunk(FileHeader h, int index)
						throws DbException {
					return privateGroupManager.getFileChunk(h, index);
				}

				@Override
				public InputStream getFile(FileHeader h) throws DbException {
					return privateGroupManager.getFile(h);
				}
			};
		}
		if (SDK_INT >= 23) {
			source.setValue(new Source(new ChunkDataSource(header, reader),
					null));
		} else {
			// MediaPlayer can't read from a custom source before API 23, so
			// decrypt the file to the app's private cache and play it from
			// there. The copy is deleted when the player is closed.
			FileReader r = reader;
			ioExecutor.execute(() -> {
				try {
					File dir =
							new File(getApplication().getCacheDir(), "media");
					//noinspection ResultOfMethodCallIgnored
					dir.mkdirs();
					File f = File.createTempFile("media", null, dir);
					tempFile = f;
					copyAndClose(r.getFile(header), new FileOutputStream(f));
					source.postValue(new Source(null, f));
				} catch (IOException | DbException e) {
					logException(LOG, WARNING, e);
					loadError.postEvent(true);
				}
			});
		}
	}

	LiveData<Source> getSource() {
		return source;
	}

	LiveEvent<Boolean> getLoadError() {
		return loadError;
	}

	LiveEvent<Boolean> getSaveError() {
		return saveError;
	}

	/**
	 * Copies the file to the given location chosen by the user.
	 */
	void save(Uri uri) {
		FileHeader h = header;
		FileReader r = reader;
		if (h == null || r == null) throw new IllegalStateException();
		ioExecutor.execute(() -> {
			try {
				InputStream is = r.getFile(h);
				OutputStream os = getApplication().getContentResolver()
						.openOutputStream(uri);
				if (os == null) throw new IOException("Cannot open " + uri);
				copyAndClose(is, os);
				saveError.postEvent(false);
			} catch (IOException | DbException e) {
				logException(LOG, WARNING, e);
				saveError.postEvent(true);
			}
		});
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		File f = tempFile;
		if (f != null) {
			ioExecutor.execute(() -> {
				//noinspection ResultOfMethodCallIgnored
				f.delete();
			});
		}
	}

	/**
	 * Lets the media player read a file straight from its chunks in the
	 * database, so the decrypted file never touches the disk. Every chunk
	 * except the last holds the same number of bytes, so the chunk holding
	 * any byte offset can be found by division. The most recent chunks are
	 * cached because players read in small pieces.
	 */
	@RequiresApi(23)
	private static class ChunkDataSource extends MediaDataSource {

		private static final int CACHE_SIZE = 4;

		private final FileHeader header;
		private final FileReader reader;
		private final Map<Integer, byte[]> cache =
				new LinkedHashMap<Integer, byte[]>(CACHE_SIZE, 0.75f, true) {
					@Override
					protected boolean removeEldestEntry(
							Map.Entry<Integer, byte[]> eldest) {
						return size() > CACHE_SIZE;
					}
				};

		private ChunkDataSource(FileHeader header, FileReader reader) {
			this.header = header;
			this.reader = reader;
		}

		@Override
		public synchronized int readAt(long position, byte[] buffer,
				int offset, int size) throws IOException {
			if (position >= header.getSize()) return -1;
			int total = 0;
			while (size > 0 && position < header.getSize()) {
				int index = (int) (position / FILE_CHUNK_PAYLOAD_LENGTH);
				int within = (int) (position % FILE_CHUNK_PAYLOAD_LENGTH);
				byte[] chunk = getChunk(index);
				int available = chunk.length - within;
				if (available <= 0) break;
				int n = Math.min(available, size);
				System.arraycopy(chunk, within, buffer, offset, n);
				position += n;
				offset += n;
				size -= n;
				total += n;
			}
			return total;
		}

		private byte[] getChunk(int index) throws IOException {
			byte[] chunk = cache.get(index);
			if (chunk == null) {
				try {
					chunk = reader.getFileChunk(header, index);
				} catch (DbException e) {
					throw new IOException(e);
				}
				cache.put(index, chunk);
			}
			return chunk;
		}

		@Override
		public long getSize() {
			return header.getSize();
		}

		@Override
		public synchronized void close() {
			cache.clear();
		}
	}
}
