package org.briarproject.briar.android.blog;

import android.app.Application;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.R;
import org.briarproject.briar.android.attachment.AttachmentCreator;
import org.briarproject.briar.android.attachment.AttachmentCreatorFactory;
import org.briarproject.briar.android.attachment.AttachmentManager;
import org.briarproject.briar.android.attachment.AttachmentResult;
import org.briarproject.briar.android.attachment.BlogAttachmentStore;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.attachment.InsufficientStorageException;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogPost;
import org.briarproject.briar.api.blog.BlogPostFactory;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.Collections.singletonList;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_NAME_LENGTH;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;

/**
 * Composes one blog or channel post. Images and files are stored as soon as
 * the user picks them, so they can be large without holding up the post,
 * and the post that reveals them is written when the user publishes.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
class WriteBlogPostViewModel extends DbViewModel
		implements AttachmentManager {

	private static final Logger LOG =
			getLogger(WriteBlogPostViewModel.class.getName());

	private final IdentityManager identityManager;
	private final BlogManager blogManager;
	private final BlogPostFactory blogPostFactory;
	private final ChannelManager channelManager;
	private final AttachmentCreator attachmentCreator;
	private final Clock clock;
	@IoExecutor
	private final Executor ioExecutor;

	private final MutableLiveData<GroupId> groupId = new MutableLiveData<>();
	// The files the user has attached so far, already stored in chunks
	private final List<FileHeader> fileHeaders = new ArrayList<>();
	private final MutableLiveData<List<FileHeader>> attachedFiles =
			new MutableLiveData<>(fileHeaders);
	// A string resource explaining why a file could not be attached
	private final MutableLiveEvent<Integer> fileError =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> published =
			new MutableLiveEvent<>();
	// UI thread only. Set when this screen has gone away, so a file that
	// finishes being stored after that is deleted rather than attached to
	// nothing
	private boolean cleared = false;
	// UI thread only. Set when a post referencing the attached files has
	// been written, so they are no longer ours to delete
	private boolean filesPublished = false;

	@Inject
	WriteBlogPostViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			@IoExecutor Executor ioExecutor,
			IdentityManager identityManager,
			BlogManager blogManager,
			BlogPostFactory blogPostFactory,
			ChannelManager channelManager,
			AttachmentCreatorFactory attachmentCreatorFactory,
			Clock clock) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.ioExecutor = ioExecutor;
		this.identityManager = identityManager;
		this.blogManager = blogManager;
		this.blogPostFactory = blogPostFactory;
		this.channelManager = channelManager;
		this.clock = clock;
		attachmentCreator = attachmentCreatorFactory
				.create(new BlogAttachmentStore(blogManager));
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		// Deletes the images that were never published. Once Publish is
		// tapped they are the post's, even if it is still being stored.
		if (!filesPublished) attachmentCreator.cancel();
		// A file is stored as soon as it's picked, so one attached to a
		// post that was never published would be left in the database
		// with nothing to reveal it and nothing to delete it
		cleared = true;
		if (!filesPublished) deleteFiles(new ArrayList<>(fileHeaders));
		fileHeaders.clear();
	}

	/**
	 * Deletes files that were stored for a post that will never be
	 * published, along with their chunks.
	 */
	private void deleteFiles(List<FileHeader> files) {
		if (files.isEmpty()) return;
		runOnDbThread(() -> {
			for (FileHeader f : files) {
				try {
					blogManager.removeFile(f);
				} catch (DbException e) {
					logException(LOG, WARNING, e);
				}
			}
		});
	}

	@UiThread
	void setGroupId(GroupId g) {
		groupId.setValue(g);
	}

	/**
	 * Stores a file in chunks so a post can reveal it when published.
	 */
	void attachFile(Uri uri) {
		GroupId g = groupId.getValue();
		if (g == null) throw new IllegalStateException();
		ioExecutor.execute(() -> {
			ContentResolver resolver =
					getApplication().getContentResolver();
			String name = null;
			long size = -1;
			try (Cursor c = resolver.query(uri, null, null, null, null)) {
				if (c != null && c.moveToFirst()) {
					int nameCol =
							c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
					if (nameCol != -1) name = c.getString(nameCol);
					int sizeCol = c.getColumnIndex(OpenableColumns.SIZE);
					if (sizeCol != -1 && !c.isNull(sizeCol))
						size = c.getLong(sizeCol);
				}
			} catch (Exception e) {
				logException(LOG, WARNING, e);
			}
			if (size > MAX_FILE_SIZE) {
				fileError.postEvent(R.string.file_too_big);
				return;
			}
			String contentType = resolver.getType(uri);
			if (contentType == null ||
					contentType.length() > MAX_CONTENT_TYPE_BYTES) {
				contentType = "application/octet-stream";
			}
			if (name == null || name.isEmpty()) {
				name = "file." + getExtension(contentType);
			} else if (name.length() > MAX_FILE_NAME_LENGTH / 4) {
				// Keep well within the byte limit, whatever the characters
				name = name.substring(0, MAX_FILE_NAME_LENGTH / 4);
			}
			// The file is read twice while it's stored, so the source
			// opens a fresh stream each time it's asked
			StreamSource source = () -> {
				InputStream in = resolver.openInputStream(uri);
				if (in == null) throw new IOException("Cannot open " + uri);
				return in;
			};
			try {
				FileHeader header = blogManager.addLocalFile(g,
						clock.currentTimeMillis(), name, contentType, source);
				androidExecutor.runOnUiThread(() -> {
					if (cleared) {
						// The screen went away while the file was being
						// stored, so there will be no post to reveal it
						deleteFiles(singletonList(header));
						return;
					}
					fileHeaders.add(header);
					attachedFiles.setValue(fileHeaders);
				});
			} catch (FileTooBigException e) {
				fileError.postEvent(R.string.file_too_big);
			} catch (InsufficientStorageException e) {
				fileError.postEvent(R.string.file_no_storage);
			} catch (IOException | DbException e) {
				logException(LOG, WARNING, e);
				fileError.postEvent(R.string.file_send_failed);
			}
		});
	}

	private String getExtension(String contentType) {
		int slash = contentType.indexOf('/');
		return slash == -1 ? "bin" : contentType.substring(slash + 1);
	}

	/**
	 * Writes the post, signed by the channel's key pair if this is a
	 * channel we own, and by our own identity otherwise.
	 */
	void publish(@Nullable String text, List<AttachmentHeader> attachments) {
		GroupId g = groupId.getValue();
		if (g == null) throw new IllegalStateException();
		List<FileHeader> files = new ArrayList<>(fileHeaders);
		// From here the files are the post's, so leaving this screen
		// before the post is stored must not delete them under it
		filesPublished = true;
		runOnDbThread(() -> {
			try {
				MessageId postId;
				// A channel's posts are signed by the channel's own key
				// pair rather than by our identity
				if (channelManager.getChannel(g) != null) {
					postId = channelManager.post(g, text, attachments, files);
				} else {
					LocalAuthor author = identityManager.getLocalAuthor();
					// After everything already in this blog, so the file
					// it is published as only grows at the end
					long timestamp = blogManager.getNextTimestamp(g,
							clock.currentTimeMillis());
					BlogPost p;
					if (attachments.isEmpty() && files.isEmpty()) {
						p = blogPostFactory.createBlogPost(g, timestamp, null,
								author, requireText(text));
					} else {
						p = blogPostFactory.createBlogPost(g, timestamp, null,
								author, text, attachments, files);
					}
					blogManager.addLocalPost(p);
					postId = p.getMessage().getId();
				}
				// The images and files have been published, so they are no
				// longer deleted when this screen goes away
				androidExecutor.runOnUiThread(() ->
						attachmentCreator.onAttachmentsSent(postId));
				published.postEvent(true);
			} catch (DbException | GeneralSecurityException
					| FormatException e) {
				logException(LOG, WARNING, e);
				published.postEvent(false);
			}
		});
	}

	private String requireText(@Nullable String text) {
		if (text == null) throw new IllegalArgumentException();
		return text;
	}

	LiveData<List<FileHeader>> getAttachedFiles() {
		return attachedFiles;
	}

	LiveEvent<Integer> getFileError() {
		return fileError;
	}

	LiveEvent<Boolean> getPublished() {
		return published;
	}

	// AttachmentManager, used by the text input's attachment controller

	@Override
	public LiveData<AttachmentResult> storeAttachments(Collection<Uri> uris,
			boolean restart) {
		if (restart) {
			// The activity was recreated, the images are already being
			// created by the existing task
			return attachmentCreator.getLiveAttachments();
		}
		return attachmentCreator.storeAttachments(groupId, uris);
	}

	@Override
	public List<AttachmentHeader> getAttachmentHeadersForSending() {
		return attachmentCreator.getAttachmentHeadersForSending();
	}

	@Override
	public void cancel() {
		attachmentCreator.cancel();
	}
}
