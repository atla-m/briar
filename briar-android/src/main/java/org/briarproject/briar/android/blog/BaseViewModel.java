package org.briarproject.briar.android.blog;

import android.app.Application;
import android.net.Uri;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.attachment.AttachmentRetriever;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.LiveResult;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.attachment.event.FileProgressEvent;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogCommentHeader;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogPostHeader;
import org.briarproject.briar.util.HtmlUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static java.util.Objects.requireNonNull;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.LogUtils.now;

@NotNullByDefault
abstract class BaseViewModel extends DbViewModel implements EventListener {

	private static final Logger LOG = getLogger(BaseViewModel.class.getName());

	private final EventBus eventBus;
	protected final IdentityManager identityManager;
	protected final AndroidNotificationManager notificationManager;
	protected final BlogManager blogManager;
	protected final AttachmentRetriever attachmentRetriever;
	@IoExecutor
	private final Executor ioExecutor;

	protected final MutableLiveData<LiveResult<ListUpdate>> blogPosts =
			new MutableLiveData<>();

	// The ID of a post whose images or files have changed state, so the
	// list can redraw that post
	private final MutableLiveData<MessageId> attachmentUpdated =
			new MutableLiveData<>();
	// UiThread
	private final List<AttachmentSubscription> attachmentSubscriptions =
			new ArrayList<>();
	// The posts that share files, keyed by manifest ID, so progress events
	// for a file can be routed to the post that shares it. UiThread
	private final Map<MessageId, BlogPostItem> filePosts = new HashMap<>();
	// Whether each blog we've loaded a post from is a channel. Written
	// on the database thread and read there too, but a ViewModel can
	// outlive the thread that made it, so keep it concurrent
	private final Map<GroupId, Boolean> channels = new ConcurrentHashMap<>();
	// true if there was an error, false if the file was saved
	private final MutableLiveEvent<Boolean> saveError =
			new MutableLiveEvent<>();

	BaseViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			EventBus eventBus,
			IdentityManager identityManager,
			AndroidNotificationManager notificationManager,
			BlogManager blogManager,
			AttachmentRetriever attachmentRetriever,
			@IoExecutor Executor ioExecutor) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.eventBus = eventBus;
		this.identityManager = identityManager;
		this.notificationManager = notificationManager;
		this.blogManager = blogManager;
		this.attachmentRetriever = attachmentRetriever;
		this.ioExecutor = ioExecutor;
		eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
		clearAttachmentSubscriptions();
	}

	// Images and files. They may arrive before or after the post that
	// references them, so each post's items are observed and the list is
	// told to redraw the post when one changes state.

	/**
	 * Handles a file progress event for one of this screen's posts.
	 * Subclasses call this from their event handler.
	 */
	protected void onFileProgress(FileProgressEvent p) {
		if (p.isComplete()) {
			// A chunked image is referenced by its manifest ID and can be
			// shown once all of its chunks have arrived
			runOnDbThread(() ->
					attachmentRetriever.loadAttachmentItem(p.getManifestId()));
		}
		androidExecutor.runOnUiThread(() -> {
			BlogPostItem item = filePosts.get(p.getManifestId());
			if (item == null) return;
			for (FileHeader h : item.getFileHeaders()) {
				if (h.getManifestId().equals(p.getManifestId()))
					loadFileStatus(item, h);
			}
		});
	}

	/**
	 * Handles an image arriving for one of this screen's posts.
	 * Subclasses call this from their event handler.
	 */
	protected void onAttachmentReceived(MessageId messageId) {
		runOnDbThread(() ->
				attachmentRetriever.loadAttachmentItem(messageId));
	}

	@UiThread
	protected void loadAttachments(List<BlogPostItem> items) {
		clearAttachmentSubscriptions();
		filePosts.clear();
		for (BlogPostItem item : items) loadAttachments(item);
	}

	@UiThread
	protected void loadAttachments(BlogPostItem item) {
		for (FileHeader h : item.getFileHeaders()) {
			filePosts.put(h.getManifestId(), item);
			loadFileStatus(item, h);
		}
		List<AttachmentHeader> headers = item.getAttachmentHeaders();
		if (headers.isEmpty()) return;
		List<LiveData<AttachmentItem>> liveDataList =
				attachmentRetriever.getAttachmentItems(headers);
		List<AttachmentItem> attachments = new ArrayList<>(headers.size());
		for (LiveData<AttachmentItem> liveData : liveDataList) {
			attachments.add(requireNonNull(liveData.getValue()));
			AttachmentSubscription s =
					new AttachmentSubscription(item, liveData);
			attachmentSubscriptions.add(s);
			liveData.observeForever(s);
		}
		item.setAttachments(attachments);
	}

	@UiThread
	private void loadFileStatus(BlogPostItem item, FileHeader h) {
		runOnDbThread(() -> {
			try {
				FileStatus status = blogManager.getFileStatus(h);
				androidExecutor.runOnUiThread(() -> {
					// Only redraw if this post is still being shown
					if (filePosts.get(h.getManifestId()) == item &&
							item.updateFileStatus(status)) {
						attachmentUpdated.setValue(item.getId());
					}
				});
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@UiThread
	private void clearAttachmentSubscriptions() {
		for (AttachmentSubscription s : attachmentSubscriptions) s.remove();
		attachmentSubscriptions.clear();
	}

	private class AttachmentSubscription implements Observer<AttachmentItem> {

		private final BlogPostItem item;
		private final LiveData<AttachmentItem> liveData;

		private AttachmentSubscription(BlogPostItem item,
				LiveData<AttachmentItem> liveData) {
			this.item = item;
			this.liveData = liveData;
		}

		@Override
		public void onChanged(AttachmentItem attachment) {
			if (item.updateAttachments(attachment)) {
				attachmentUpdated.setValue(item.getId());
			}
			// Once the image is loaded (or failed), stop observing
			if (attachment.getState().isFinal()) remove();
		}

		private void remove() {
			liveData.removeObserver(this);
		}
	}

	LiveData<MessageId> getAttachmentUpdated() {
		return attachmentUpdated;
	}

	LiveEvent<Boolean> getSaveError() {
		return saveError;
	}

	/**
	 * Copies a fully received file to the location the user chose.
	 */
	/**
	 * Asks for a file its sender held back because it is too large to be
	 * sent without being asked for. The file's row updates when the request
	 * is stored, and again as the chunks arrive.
	 */
	void requestFile(FileHeader header) {
		runOnDbThread(() -> {
			try {
				blogManager.requestFile(header);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void saveFile(FileHeader header, Uri uri) {
		ioExecutor.execute(() -> {
			try {
				InputStream is = blogManager.getFile(header);
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

	@DatabaseExecutor
	protected List<BlogPostItem> loadBlogPosts(Transaction txn, GroupId groupId)
			throws DbException {
		long start = now();
		List<BlogPostHeader> headers =
				blogManager.getPostHeaders(txn, groupId);
		logDuration(LOG, "Loading headers", start);
		List<BlogPostItem> items = new ArrayList<>(headers.size());
		start = now();
		for (BlogPostHeader h : headers) {
			BlogPostItem item = getItem(txn, h);
			items.add(item);
		}
		logDuration(LOG, "Loading bodies", start);
		return items;
	}

	@DatabaseExecutor
	protected BlogPostItem getItem(Transaction txn, BlogPostHeader h)
			throws DbException {
		boolean channel = isChannel(txn, h.getGroupId());
		String text;
		if (h instanceof BlogCommentHeader) {
			BlogCommentHeader c = (BlogCommentHeader) h;
			BlogCommentItem item = new BlogCommentItem(c, channel);
			text = getPostText(txn, item.getPostHeader().getId());
			item.setText(text);
			return item;
		} else {
			text = getPostText(txn, h.getId());
			return new BlogPostItem(h, text, channel);
		}
	}

	/**
	 * Returns true if the given group is a channel. Whether a blog is a
	 * channel is fixed when it's created, so the answer is cached: the
	 * feed loads every post of every blog and would otherwise look the
	 * same blog up once per post.
	 */
	@DatabaseExecutor
	private boolean isChannel(Transaction txn, GroupId g) throws DbException {
		Boolean cached = channels.get(g);
		if (cached != null) return cached;
		boolean channel = blogManager.getBlog(txn, g).isChannel();
		channels.put(g, channel);
		return channel;
	}

	@DatabaseExecutor
	private String getPostText(Transaction txn, MessageId m)
			throws DbException {
		return HtmlUtils.cleanArticle(blogManager.getPostText(txn, m));
	}

	LiveData<LiveResult<BlogPostItem>> loadBlogPost(GroupId g, MessageId m) {
		MutableLiveData<LiveResult<BlogPostItem>> result =
				new MutableLiveData<>();
		runOnDbThread(true, txn -> {
			long start = now();
			BlogPostHeader header = blogManager.getPostHeader(txn, g, m);
			BlogPostItem item = getItem(txn, header);
			logDuration(LOG, "Loading post", start);
			result.postValue(new LiveResult<>(item));
		}, e -> {
			logException(LOG, WARNING, e);
			result.postValue(new LiveResult<>(e));
		});
		return result;
	}

	protected void onBlogPostAdded(BlogPostHeader header, boolean local) {
		runOnDbThread(true, txn -> {
			BlogPostItem item = getItem(txn, header);
			txn.attach(() -> onBlogPostItemAdded(item, local));
		}, this::handleException);
	}

	@UiThread
	private void onBlogPostItemAdded(BlogPostItem item, boolean local) {
		loadAttachments(item);
		List<BlogPostItem> items = addListItem(getBlogPostItems(), item);
		if (items != null) {
			Collections.sort(items);
			blogPosts.setValue(new LiveResult<>(new ListUpdate(local, items)));
		}
	}

	void repeatPost(BlogPostItem item, @Nullable String comment) {
		// A channel post is shared by sharing the channel. Reblogging it
		// would sign it into the reblogger's own blog and carry their
		// identity to everyone downstream, including people who are
		// contacts of nobody else in the chain
		if (item.isChannel()) throw new IllegalArgumentException();
		runOnDbThread(() -> {
			try {
				LocalAuthor a = identityManager.getLocalAuthor();
				Blog b = blogManager.getPersonalBlog(a);
				BlogPostHeader h = item.getHeader();
				blogManager.addLocalComment(a, b.getId(), comment, h);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	/**
	 * Publishes a freshly loaded list of posts, loading the images and
	 * files they carry first.
	 */
	@UiThread
	protected void setBlogPosts(LiveResult<ListUpdate> result) {
		ListUpdate update = result.getResultOrNull();
		if (update != null) loadAttachments(update.getItems());
		blogPosts.setValue(result);
	}

	LiveData<LiveResult<ListUpdate>> getBlogPosts() {
		return blogPosts;
	}

	@UiThread
	@Nullable
	protected List<BlogPostItem> getBlogPostItems() {
		LiveResult<ListUpdate> value = blogPosts.getValue();
		if (value == null) return null;
		ListUpdate result = value.getResultOrNull();
		return result == null ? null : result.getItems();
	}

	/**
	 * Call this after {@link ListUpdate#getPostAddedWasLocal()} was processed.
	 * This prevents it from getting processed again.
	 */
	@UiThread
	void resetLocalUpdate() {
		LiveResult<ListUpdate> value = blogPosts.getValue();
		if (value == null) return;
		ListUpdate result = value.getResultOrNull();
		result.postAddedWasLocal = null;
	}

	static class ListUpdate {

		@Nullable
		private Boolean postAddedWasLocal;
		private final List<BlogPostItem> items;

		ListUpdate(@Nullable Boolean postAddedWasLocal,
				List<BlogPostItem> items) {
			this.postAddedWasLocal = postAddedWasLocal;
			this.items = items;
		}

		/**
		 * @return null when not a single post was added with this update.
		 * true when a single post was added locally and false if remotely.
		 */
		@Nullable
		public Boolean getPostAddedWasLocal() {
			return postAddedWasLocal;
		}

		public List<BlogPostItem> getItems() {
			return items;
		}
	}
}
