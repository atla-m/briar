package org.briarproject.briar.android.privategroup.conversation;

import android.app.Application;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.android.attachment.AttachmentCreator;
import org.briarproject.briar.android.attachment.AttachmentCreatorFactory;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.attachment.AttachmentManager;
import org.briarproject.briar.android.attachment.AttachmentResult;
import org.briarproject.briar.android.attachment.AttachmentRetriever;
import org.briarproject.briar.android.attachment.GroupAttachmentStore;
import org.briarproject.briar.R;
import org.briarproject.briar.android.sharing.SharingController;
import org.briarproject.briar.android.threaded.ThreadListViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.LiveResult;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.client.MessageTracker.GroupCount;
import org.briarproject.briar.api.client.MessageTree;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.privategroup.GroupMember;
import org.briarproject.briar.api.privategroup.GroupMessage;
import org.briarproject.briar.api.privategroup.GroupMessageFactory;
import org.briarproject.briar.api.privategroup.GroupMessageHeader;
import org.briarproject.briar.api.privategroup.JoinMessageHeader;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.event.ContactRelationshipRevealedEvent;
import org.briarproject.briar.api.privategroup.event.GroupAttachmentReceivedEvent;
import org.briarproject.briar.api.privategroup.event.GroupDissolvedEvent;
import org.briarproject.briar.api.attachment.event.FileProgressEvent;
import org.briarproject.briar.api.privategroup.event.GroupInvitationResponseReceivedEvent;
import org.briarproject.briar.api.privategroup.event.GroupMessageAddedEvent;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationResponse;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import static java.lang.Math.max;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.logging.Level.WARNING;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.LogUtils.now;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_NAME_LENGTH;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class GroupViewModel extends ThreadListViewModel<GroupMessageItem>
		implements AttachmentManager {

	private static final Logger LOG = getLogger(GroupViewModel.class.getName());

	private final PrivateGroupManager privateGroupManager;
	private final GroupMessageFactory groupMessageFactory;
	private final AttachmentRetriever attachmentRetriever;
	private final AttachmentCreator attachmentCreator;
	@IoExecutor
	private final Executor ioExecutor;

	// The ID of a post whose attachments or files have changed state, so
	// the list can redraw that post
	private final MutableLiveData<MessageId> attachmentUpdated =
			new MutableLiveData<>();
	// UiThread
	private final List<AttachmentSubscription> attachmentSubscriptions =
			new ArrayList<>();
	// The posts that share files, keyed by manifest ID, so progress events
	// for a file can be routed to its post. UiThread
	private final Map<MessageId, GroupMessageItem> filePosts =
			new HashMap<>();
	// A string resource explaining why a file could not be sent
	private final MutableLiveEvent<Integer> fileError =
			new MutableLiveEvent<>();

	private final MutableLiveData<PrivateGroup> privateGroup =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> isCreator = new MutableLiveData<>();
	private final MutableLiveData<Boolean> isDissolved =
			new MutableLiveData<>();

	@Inject
	GroupViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			EventBus eventBus,
			IdentityManager identityManager,
			AndroidNotificationManager notificationManager,
			SharingController sharingController,
			@CryptoExecutor Executor cryptoExecutor,
			Clock clock,
			MessageTracker messageTracker,
			PrivateGroupManager privateGroupManager,
			GroupMessageFactory groupMessageFactory,
			AttachmentRetriever attachmentRetriever,
			AttachmentCreatorFactory attachmentCreatorFactory,
			@IoExecutor Executor ioExecutor) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor,
				identityManager, notificationManager, sharingController,
				cryptoExecutor, clock, messageTracker, eventBus);
		this.privateGroupManager = privateGroupManager;
		this.groupMessageFactory = groupMessageFactory;
		this.attachmentRetriever = attachmentRetriever;
		this.attachmentCreator = attachmentCreatorFactory
				.create(new GroupAttachmentStore(privateGroupManager));
		this.ioExecutor = ioExecutor;
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		attachmentCreator.cancel(); // also deletes unsent attachments
		clearAttachmentSubscriptions();
	}

	@Override
	protected List<GroupMessageItem> orderItems(
			MessageTree<GroupMessageItem> tree) {
		// Groups are shown as a flat chat in chronological order, with
		// replies quoting their parent, rather than as a nested thread
		List<GroupMessageItem> items = new ArrayList<>(tree.depthFirstOrder());
		Collections.sort(items, (a, b) ->
				Long.compare(a.getTimestamp(), b.getTimestamp()));
		return items;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof GroupMessageAddedEvent) {
			GroupMessageAddedEvent g = (GroupMessageAddedEvent) e;
			// only act on non-local messages in this group
			if (!g.isLocal() && g.getGroupId().equals(groupId)) {
				LOG.info("Group message received, adding...");
				GroupMessageItem item = buildItem(g.getHeader(), g.getText());
				addItem(item, false);
				// In case the join message comes from the creator,
				// we need to reload the sharing contacts
				// in case it was delayed and the sharing count is wrong (#850).
				if (item instanceof JoinMessageItem &&
						(((JoinMessageItem) item).isInitial())) {
					loadSharingContacts();
				}
			}
		} else if (e instanceof GroupInvitationResponseReceivedEvent) {
			GroupInvitationResponseReceivedEvent g =
					(GroupInvitationResponseReceivedEvent) e;
			GroupInvitationResponse r = g.getMessageHeader();
			if (r.getShareableId().equals(groupId) && r.wasAccepted()) {
				sharingController.add(g.getContactId());
			}
		} else if (e instanceof ContactRelationshipRevealedEvent) {
			ContactRelationshipRevealedEvent c =
					(ContactRelationshipRevealedEvent) e;
			if (c.getGroupId().equals(groupId)) {
				sharingController.add(c.getContactId());
			}
		} else if (e instanceof GroupDissolvedEvent) {
			GroupDissolvedEvent g = (GroupDissolvedEvent) e;
			if (g.getGroupId().equals(groupId)) {
				isDissolved.setValue(true);
			}
		} else if (e instanceof GroupAttachmentReceivedEvent) {
			GroupAttachmentReceivedEvent a = (GroupAttachmentReceivedEvent) e;
			if (a.getGroupId().equals(groupId)) {
				LOG.info("Group attachment received");
				runOnDbThread(() -> attachmentRetriever
						.loadAttachmentItem(a.getMessageId()));
			}
		} else if (e instanceof FileProgressEvent) {
			FileProgressEvent p = (FileProgressEvent) e;
			if (p.getGroupId().equals(groupId)) {
				if (p.isComplete()) {
					// A chunked image is referenced by its manifest ID and
					// can be shown once all of its chunks have arrived
					LOG.info("Chunked file complete");
					runOnDbThread(() -> attachmentRetriever
							.loadAttachmentItem(p.getManifestId()));
				}
				// If the file belongs to a post, update its progress row
				androidExecutor.runOnUiThread(() -> {
					GroupMessageItem item = filePosts.get(p.getManifestId());
					if (item == null) return;
					for (FileHeader h : item.getFileHeaders()) {
						if (h.getManifestId().equals(p.getManifestId()))
							loadFileStatus(item, h);
					}
				});
			}
		} else {
			super.eventOccurred(e);
		}
	}

	@Override
	protected void performInitialLoad() {
		super.performInitialLoad();
		loadPrivateGroup(groupId);
	}

	@Override
	protected void clearNotifications() {
		notificationManager.clearGroupMessageNotification(groupId);
	}

	private void loadPrivateGroup(GroupId groupId) {
		runOnDbThread(() -> {
			try {
				PrivateGroup g = privateGroupManager.getPrivateGroup(groupId);
				privateGroup.postValue(g);
				Author author = identityManager.getLocalAuthor();
				isCreator.postValue(g.getCreator().equals(author));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@Override
	public void loadItems() {
		loadFromDb(txn -> {
			// check first if group is dissolved
			isDissolved
					.postValue(privateGroupManager.isDissolved(txn, groupId));
			// now continue to load the items
			long start = now();
			List<GroupMessageHeader> headers =
					privateGroupManager.getHeaders(txn, groupId);
			logDuration(LOG, "Loading headers", start);
			start = now();
			List<GroupMessageItem> items = new ArrayList<>();
			for (GroupMessageHeader header : headers) {
				items.add(loadItem(txn, header));
			}
			logDuration(LOG, "Loading bodies and creating items", start);
			return items;
		}, this::setItems);
	}

	private GroupMessageItem loadItem(Transaction txn,
			GroupMessageHeader header) throws DbException {
		String text;
		if (header instanceof JoinMessageHeader) {
			// will be looked up later
			text = "";
		} else {
			text = privateGroupManager.getMessageText(txn, header.getId());
		}
		return buildItem(header, text);
	}

	private GroupMessageItem buildItem(GroupMessageHeader header, String text) {
		if (header instanceof JoinMessageHeader) {
			return new JoinMessageItem((JoinMessageHeader) header, text);
		}
		return new GroupMessageItem(header, text);
	}

	@Override
	public void createAndStoreMessage(String text,
			@Nullable MessageId parentId) {
		createAndStoreMessage(text, emptyList(), parentId);
	}

	/**
	 * Shares the audio or video file at the given URI with the group: the
	 * file is stored in chunks and a post referencing it is created.
	 */
	void sendFile(Uri uri) {
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
				LocalAuthor author = identityManager.getLocalAuthor();
				MessageId previousMsgId =
						privateGroupManager.getPreviousMsgId(groupId);
				GroupCount count = privateGroupManager.getGroupCount(groupId);
				long timestamp = max(clock.currentTimeMillis(),
						count.getLatestMsgTime() + 1);
				// The manifest takes this timestamp and the chunks the next
				// one, so the post that reveals the file comes after both
				FileHeader header = privateGroupManager.addLocalFile(
						groupId, timestamp, name, contentType, source);
				createMessage(null, emptyList(), singletonList(header),
						timestamp + 2, null, author, previousMsgId);
			} catch (FileTooBigException e) {
				fileError.postEvent(R.string.file_too_big);
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

	LiveEvent<Integer> getFileError() {
		return fileError;
	}

	// true if there was an error, false if the file was saved
	private final MutableLiveEvent<Boolean> saveError =
			new MutableLiveEvent<>();

	LiveEvent<Boolean> getSaveError() {
		return saveError;
	}

	/**
	 * Copies a fully received file to the given location chosen by the
	 * user.
	 */
	void saveFile(FileHeader header, Uri uri) {
		ioExecutor.execute(() -> {
			try {
				InputStream is = privateGroupManager.getFile(header);
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

	/**
	 * Creates and stores a post with optional text and attachments. The
	 * attachments must have been stored via {@link #storeAttachments}.
	 */
	void createAndStoreMessage(@Nullable String text,
			List<AttachmentHeader> attachmentHeaders,
			@Nullable MessageId parentId) {
		if (text == null && attachmentHeaders.isEmpty())
			throw new IllegalArgumentException();
		runOnDbThread(() -> {
			try {
				LocalAuthor author = identityManager.getLocalAuthor();
				MessageId previousMsgId =
						privateGroupManager.getPreviousMsgId(groupId);
				GroupCount count = privateGroupManager.getGroupCount(groupId);
				long timestamp = count.getLatestMsgTime();
				timestamp = max(clock.currentTimeMillis(), timestamp + 1);
				createMessage(text, attachmentHeaders, emptyList(), timestamp,
						parentId, author, previousMsgId);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	private void createMessage(@Nullable String text,
			List<AttachmentHeader> attachmentHeaders,
			List<FileHeader> fileHeaders, long timestamp,
			@Nullable MessageId parentId, LocalAuthor author,
			MessageId previousMsgId) {
		cryptoExecutor.execute(() -> {
			LOG.info("Creating group message...");
			GroupMessage msg = groupMessageFactory.createGroupMessage(groupId,
					timestamp, parentId, author, text, attachmentHeaders,
					fileHeaders, previousMsgId);
			storePost(msg, text == null ? "" : text, fileHeaders);
		});
	}

	private void storePost(GroupMessage msg, String text,
			List<FileHeader> fileHeaders) {
		runOnDbThread(false, txn -> {
			long start = now();
			GroupMessageHeader header =
					privateGroupManager.addLocalMessage(txn, msg);
			logDuration(LOG, "Storing group message", start);
			txn.attach(() -> {
				// The attachments have been marked as sent, forget them
				if (!header.getAttachmentHeaders().isEmpty())
					attachmentCreator.onAttachmentsSent(header.getId());
				addItem(buildItem(header, text), true);
			});
		}, e -> {
			handleException(e);
			// A file is stored before the post that reveals it, so if the
			// post can't be stored the file is left with nothing to
			// reveal it and nothing to delete it
			for (FileHeader f : fileHeaders) {
				try {
					privateGroupManager.removeFile(f);
				} catch (DbException ignored) {
				}
			}
			if (!fileHeaders.isEmpty())
				fileError.postEvent(R.string.file_send_failed);
		});
	}

	// Attachment loading. Attachments may arrive before or after the post
	// that references them, so each post's attachment items are observed
	// and the list is told to redraw the post when one changes state.

	@Override
	@UiThread
	protected void setItems(LiveResult<List<GroupMessageItem>> items) {
		clearAttachmentSubscriptions();
		filePosts.clear();
		List<GroupMessageItem> list = items.getResultOrNull();
		if (list != null) {
			for (GroupMessageItem item : list) {
				loadAttachments(item);
				loadFileStatuses(item);
			}
		}
		super.setItems(items);
	}

	@Override
	@UiThread
	protected void addItem(GroupMessageItem item, boolean scrollToItem) {
		loadAttachments(item);
		loadFileStatuses(item);
		super.addItem(item, scrollToItem);
	}

	@UiThread
	private void loadFileStatuses(GroupMessageItem item) {
		for (FileHeader h : item.getFileHeaders()) {
			filePosts.put(h.getManifestId(), item);
			loadFileStatus(item, h);
		}
	}

	@UiThread
	private void loadFileStatus(GroupMessageItem item, FileHeader h) {
		runOnDbThread(() -> {
			try {
				FileStatus status = privateGroupManager.getFileStatus(h);
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
	private void loadAttachments(GroupMessageItem item) {
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
	private void clearAttachmentSubscriptions() {
		for (AttachmentSubscription s : attachmentSubscriptions) s.remove();
		attachmentSubscriptions.clear();
	}

	private class AttachmentSubscription implements Observer<AttachmentItem> {

		private final GroupMessageItem item;
		private final LiveData<AttachmentItem> liveData;

		private AttachmentSubscription(GroupMessageItem item,
				LiveData<AttachmentItem> liveData) {
			this.item = item;
			this.liveData = liveData;
		}

		@Override
		public void onChanged(AttachmentItem attachment) {
			if (item.updateAttachments(attachment)) {
				attachmentUpdated.setValue(item.getId());
			}
			// Once the attachment is loaded (or failed), stop observing
			if (attachment.getState().isFinal()) remove();
		}

		private void remove() {
			liveData.removeObserver(this);
		}
	}

	LiveData<MessageId> getAttachmentUpdated() {
		return attachmentUpdated;
	}

	// AttachmentManager, used by the text input's attachment controller

	@Override
	public LiveData<AttachmentResult> storeAttachments(Collection<Uri> uris,
			boolean restart) {
		if (restart) {
			// The activity was recreated, the attachments are already
			// being created by the existing task
			return attachmentCreator.getLiveAttachments();
		}
		return attachmentCreator
				.storeAttachments(new MutableLiveData<>(groupId), uris);
	}

	@Override
	public List<AttachmentHeader> getAttachmentHeadersForSending() {
		return attachmentCreator.getAttachmentHeadersForSending();
	}

	@Override
	public void cancel() {
		attachmentCreator.cancel();
	}

	@Override
	protected void markItemRead(GroupMessageItem item) {
		runOnDbThread(() -> {
			try {
				privateGroupManager.setReadFlag(groupId, item.getId(), true);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@Override
	public void loadSharingContacts() {
		runOnDbThread(true, txn -> {
			Collection<GroupMember> members =
					privateGroupManager.getMembers(txn, groupId);
			Collection<ContactId> contactIds = new ArrayList<>();
			for (GroupMember m : members) {
				if (m.getContactId() != null)
					contactIds.add(m.getContactId());
			}
			txn.attach(() -> sharingController.addAll(contactIds));
		}, this::handleException);
	}

	void deletePrivateGroup() {
		runOnDbThread(() -> {
			try {
				privateGroupManager.removePrivateGroup(groupId);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<PrivateGroup> getPrivateGroup() {
		return privateGroup;
	}

	LiveData<Boolean> isCreator() {
		return isCreator;
	}

	LiveData<Boolean> isDissolved() {
		return isDissolved;
	}

}
