package org.briarproject.briar.android.conversation;

import android.app.Application;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchContactException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.android.attachment.AttachmentCreator;
import org.briarproject.briar.android.attachment.AttachmentManager;
import org.briarproject.briar.android.attachment.AttachmentResult;
import org.briarproject.briar.android.attachment.AttachmentRetriever;
import org.briarproject.briar.android.contact.ContactItem;
import org.briarproject.briar.android.util.UiUtils;
import org.briarproject.briar.android.view.TextSendController.SendState;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.R;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.autodelete.AutoDeleteManager;
import org.briarproject.briar.api.autodelete.UnexpectedTimerException;
import org.briarproject.briar.api.autodelete.event.AutoDeleteTimerMirroredEvent;
import org.briarproject.briar.api.avatar.event.AvatarUpdatedEvent;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.briar.api.messaging.PrivateMessageFactory;
import org.briarproject.briar.api.messaging.PrivateMessageFormat;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.attachment.event.FileProgressEvent;
import org.briarproject.briar.api.messaging.event.AttachmentReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static androidx.lifecycle.Transformations.map;
import static java.util.Objects.requireNonNull;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.LogUtils.now;
import static org.briarproject.briar.android.settings.SettingsFragment.SETTINGS_NAMESPACE;
import static org.briarproject.briar.android.util.UiUtils.observeForeverOnce;
import static org.briarproject.briar.android.view.TextSendController.SendState.ERROR;
import static org.briarproject.briar.android.view.TextSendController.SendState.SENT;
import static org.briarproject.briar.android.view.TextSendController.SendState.UNEXPECTED_TIMER;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_NAME_LENGTH;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.api.autodelete.AutoDeleteManager.DEFAULT_TIMER_DURATION;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_IMAGES;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_ONLY;

@NotNullByDefault
public class ConversationViewModel extends DbViewModel
		implements EventListener, AttachmentManager {

	private static final Logger LOG =
			getLogger(ConversationViewModel.class.getName());

	private static final String SHOW_ONBOARDING_IMAGE =
			"showOnboardingImage";
	private static final String SHOW_ONBOARDING_INTRODUCTION =
			"showOnboardingIntroduction";

	private final TransactionManager db;
	private final EventBus eventBus;
	private final MessagingManager messagingManager;
	private final ContactManager contactManager;
	private final AuthorManager authorManager;
	private final SettingsManager settingsManager;
	private final PrivateMessageFactory privateMessageFactory;
	private final AttachmentRetriever attachmentRetriever;
	private final AttachmentCreator attachmentCreator;
	private final AutoDeleteManager autoDeleteManager;
	private final ConversationManager conversationManager;

	@Nullable
	private ContactId contactId = null;
	private final MutableLiveData<ContactItem> contactItem =
			new MutableLiveData<>();
	private final LiveData<String> contactName = map(contactItem, c ->
			UiUtils.getContactDisplayName(c.getContact()));
	private final LiveData<GroupId> messagingGroupId;
	private final MutableLiveData<PrivateMessageFormat> privateMessageFormat =
			new MutableLiveData<>();
	private final MutableLiveEvent<Boolean> showImageOnboarding =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Boolean> showIntroductionOnboarding =
			new MutableLiveEvent<>();
	private final MutableLiveData<Boolean> showIntroductionAction =
			new MutableLiveData<>();
	private final MutableLiveData<Long> autoDeleteTimer =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> contactDeleted =
			new MutableLiveData<>(false);
	private final MutableLiveEvent<PrivateMessageHeader> addedHeader =
			new MutableLiveEvent<>();
	// The latest known status of a shared file, for redrawing its message
	private final MutableLiveData<FileStatus> fileStatusUpdated =
			new MutableLiveData<>();
	// A string resource explaining why a file could not be sent
	private final MutableLiveEvent<Integer> fileError = new MutableLiveEvent<>();
	// true if there was an error, false if the file was saved
	private final MutableLiveEvent<Boolean> saveError = new MutableLiveEvent<>();
	private final Clock clock;
	@IoExecutor
	private final Executor ioExecutor;

	@Inject
	ConversationViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			EventBus eventBus,
			MessagingManager messagingManager,
			ContactManager contactManager,
			AuthorManager authorManager,
			SettingsManager settingsManager,
			PrivateMessageFactory privateMessageFactory,
			AttachmentRetriever attachmentRetriever,
			AttachmentCreator attachmentCreator,
			AutoDeleteManager autoDeleteManager,
			ConversationManager conversationManager,
			Clock clock, @IoExecutor Executor ioExecutor) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.db = db;
		this.eventBus = eventBus;
		this.messagingManager = messagingManager;
		this.contactManager = contactManager;
		this.authorManager = authorManager;
		this.settingsManager = settingsManager;
		this.privateMessageFactory = privateMessageFactory;
		this.attachmentRetriever = attachmentRetriever;
		this.attachmentCreator = attachmentCreator;
		this.autoDeleteManager = autoDeleteManager;
		this.conversationManager = conversationManager;
		this.clock = clock;
		this.ioExecutor = ioExecutor;
		messagingGroupId = map(contactItem, c ->
				messagingManager.getContactGroup(c.getContact()).getId());
		eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		attachmentCreator.cancel();  // also deletes unsent attachments
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof AttachmentReceivedEvent) {
			AttachmentReceivedEvent a = (AttachmentReceivedEvent) e;
			if (a.getContactId().equals(contactId)) {
				LOG.info("Attachment received");
				runOnDbThread(() -> attachmentRetriever
						.loadAttachmentItem(a.getMessageId()));
			}
		} else if (e instanceof FileProgressEvent) {
			FileProgressEvent p = (FileProgressEvent) e;
			runOnDbThread(() -> {
				try {
					ContactId c = messagingManager.getContactId(p.getGroupId());
					if (!c.equals(contactId)) return;
					if (p.isComplete()) {
						// A chunked image is referenced by its manifest ID
						// and can be shown once all its chunks have arrived
						LOG.info("Chunked file complete");
						attachmentRetriever.loadAttachmentItem(p.getManifestId());
					}
					// If the file is shown as a row, update its progress
					FileHeader h = messagingManager.getFileHeader(p.getGroupId(),
							p.getManifestId());
					postFileStatus(messagingManager.getFileStatus(h));
				} catch (DbException ex) {
					// Not a private conversation, contact removed, or the
					// manifest hasn't arrived yet
				}
			});
		} else if (e instanceof AutoDeleteTimerMirroredEvent) {
			AutoDeleteTimerMirroredEvent a = (AutoDeleteTimerMirroredEvent) e;
			if (a.getContactId().equals(contactId)) {
				autoDeleteTimer.setValue(a.getNewTimer());
			}
		} else if (e instanceof AvatarUpdatedEvent) {
			AvatarUpdatedEvent a = (AvatarUpdatedEvent) e;
			if (a.getContactId().equals(contactId)) {
				LOG.info("Avatar updated");
				updateAvatar(a);
			}
		}
	}

	@UiThread
	private void updateAvatar(AvatarUpdatedEvent a) {
		// Make sure that contactItem has been set by the task initiated
		// by loadContact() before we update the avatar.
		observeForeverOnce(contactItem, oldContactItem -> {
			requireNonNull(oldContactItem);

			AuthorInfo oldAuthorInfo = oldContactItem.getAuthorInfo();

			AuthorInfo newAuthorInfo = new AuthorInfo(oldAuthorInfo.getStatus(),
					oldAuthorInfo.getAlias(), a.getAttachmentHeader());
			ContactItem newContactItem =
					new ContactItem(oldContactItem.getContact(), newAuthorInfo);

			contactItem.setValue(newContactItem);
		});
	}

	/**
	 * Setting the {@link ContactId} automatically triggers loading of other
	 * data.
	 */
	void setContactId(ContactId contactId) {
		if (this.contactId == null) {
			this.contactId = contactId;
			loadContact(contactId);
		} else if (!contactId.equals(this.contactId)) {
			throw new IllegalStateException();
		}
	}

	private void loadContact(ContactId contactId) {
		runOnDbThread(() -> {
			try {
				long start = now();
				Contact c = contactManager.getContact(contactId);
				AuthorInfo authorInfo = authorManager.getAuthorInfo(c);
				contactItem.postValue(new ContactItem(c, authorInfo));
				logDuration(LOG, "Loading contact", start);
				start = now();
				long timer = db.transactionWithResult(true, txn ->
						autoDeleteManager.getAutoDeleteTimer(txn, contactId));
				autoDeleteTimer.postValue(timer);
				logDuration(LOG, "Getting auto-delete timer", start);
				start = now();
				checkFeaturesAndOnboarding(contactId);
				logDuration(LOG, "Checking for image support", start);
			} catch (NoSuchContactException e) {
				contactDeleted.postValue(true);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void markMessageRead(GroupId g, MessageId m) {
		runOnDbThread(() -> {
			try {
				long start = now();
				conversationManager.setReadFlag(g, m, true);
				logDuration(LOG, "Marking read", start);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void setContactAlias(String alias) {
		runOnDbThread(() -> {
			try {
				contactManager.setContactAlias(requireNonNull(contactId),
						alias.isEmpty() ? null : alias);
				loadContact(contactId);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	@Override
	@UiThread
	public LiveData<AttachmentResult> storeAttachments(Collection<Uri> uris,
			boolean restart) {
		if (restart) {
			return attachmentCreator.getLiveAttachments();
		} else {
			// messagingGroupId is loaded with the contact
			return attachmentCreator.storeAttachments(messagingGroupId, uris);
		}
	}

	@Override
	@UiThread
	public List<AttachmentHeader> getAttachmentHeadersForSending() {
		return attachmentCreator.getAttachmentHeadersForSending();
	}

	@Override
	@UiThread
	public void cancel() {
		attachmentCreator.cancel();
	}

	@DatabaseExecutor
	private void checkFeaturesAndOnboarding(ContactId c) throws DbException {
		// check if images and auto-deletion are supported
		PrivateMessageFormat format = db.transactionWithResult(true, txn ->
				messagingManager.getContactMessageFormat(txn, c));
		if (LOG.isLoggable(INFO))
			LOG.info("PrivateMessageFormat loaded: " + format.name());
		privateMessageFormat.postValue(format);

		// check if introductions are supported
		Collection<Contact> contacts = contactManager.getContacts();
		boolean introductionSupported = contacts.size() > 1;
		showIntroductionAction.postValue(introductionSupported);

		// we only show one onboarding dialog at a time
		Settings settings = settingsManager.getSettings(SETTINGS_NAMESPACE);
		if (format != TEXT_ONLY &&
				settings.getBoolean(SHOW_ONBOARDING_IMAGE, true)) {
			onOnboardingShown(SHOW_ONBOARDING_IMAGE);
			showImageOnboarding.postEvent(true);
		} else if (introductionSupported &&
				settings.getBoolean(SHOW_ONBOARDING_INTRODUCTION, true)) {
			onOnboardingShown(SHOW_ONBOARDING_INTRODUCTION);
			showIntroductionOnboarding.postEvent(true);
		}
	}

	@DatabaseExecutor
	private void onOnboardingShown(String key) throws DbException {
		Settings settings = new Settings();
		settings.putBoolean(key, false);
		settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);
	}

	// Files of any type, sent as chunks

	/**
	 * Loads how much of each of the given files has arrived.
	 */
	void loadFileStatuses(List<FileHeader> headers) {
		runOnDbThread(() -> {
			try {
				for (FileHeader h : headers) {
					postFileStatus(messagingManager.getFileStatus(h));
				}
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	private void postFileStatus(FileStatus status) {
		// setValue on the UI thread so that no update is coalesced away
		androidExecutor.runOnUiThread(() -> fileStatusUpdated.setValue(status));
	}

	LiveData<FileStatus> getFileStatusUpdated() {
		return fileStatusUpdated;
	}

	LiveEvent<Integer> getFileError() {
		return fileError;
	}

	LiveEvent<Boolean> getSaveError() {
		return saveError;
	}

	/**
	 * Shares the file at the given URI with the contact: the file is stored
	 * in chunks and a private message referencing it is sent. The contact
	 * must support {@link PrivateMessageFormat#supportsFiles() files}.
	 */
	@UiThread
	void sendFile(Uri uri) {
		Contact contact = requireNonNull(contactItem.getValue()).getContact();
		GroupId groupId = messagingManager.getContactGroup(contact).getId();
		ContactId c = requireNonNull(contactId);
		ioExecutor.execute(() -> {
			ContentResolver resolver = getApplication().getContentResolver();
			String name = null;
			long size = -1;
			try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
				if (cursor != null && cursor.moveToFirst()) {
					int nameCol =
							cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
					if (nameCol != -1) name = cursor.getString(nameCol);
					int sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE);
					if (sizeCol != -1 && !cursor.isNull(sizeCol))
						size = cursor.getLong(sizeCol);
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
				name = "file";
			} else if (name.length() > MAX_FILE_NAME_LENGTH / 4) {
				// Keep well within the byte limit, whatever the characters
				name = name.substring(0, MAX_FILE_NAME_LENGTH / 4);
			}
			FileHeader header;
			try (InputStream in = resolver.openInputStream(uri)) {
				if (in == null) throw new IOException("Cannot open " + uri);
				// The manifest and chunks are stored outside the message's
				// transaction, as they're written in transactions of their
				// own. They aren't sent until the message references them.
				header = messagingManager.addLocalFile(groupId,
						clock.currentTimeMillis(), name, contentType, in);
			} catch (FileTooBigException e) {
				fileError.postEvent(R.string.file_too_big);
				return;
			} catch (IOException | DbException e) {
				logException(LOG, WARNING, e);
				fileError.postEvent(R.string.file_send_failed);
				return;
			}
			runOnDbThread(() -> {
				try {
					db.transaction(false, txn -> {
						long timestamp = conversationManager
								.getTimestampForOutgoingMessage(txn, c);
						long timer = autoDeleteManager
								.getAutoDeleteTimer(txn, c, timestamp);
						PrivateMessage m;
						try {
							m = privateMessageFactory.createPrivateMessage(
									groupId, timestamp, null, emptyList(),
									singletonList(header), timer);
						} catch (FormatException e) {
							throw new AssertionError(e);
						}
						messagingManager.addLocalMessage(txn, m);
						Message message = m.getMessage();
						PrivateMessageHeader h = new PrivateMessageHeader(
								message.getId(), message.getGroupId(),
								message.getTimestamp(), true, true, false,
								false, false, emptyList(),
								singletonList(header), timer);
						txn.attach(() -> addedHeader.setEvent(h));
					});
				} catch (DbException e) {
					handleException(e);
					try {
						messagingManager.removeFile(header);
					} catch (DbException ignored) {
					}
					fileError.postEvent(R.string.file_send_failed);
				}
			});
		});
	}

	/**
	 * Copies a fully received file to the given location chosen by the
	 * user.
	 */
	void saveFile(FileHeader header, Uri uri) {
		ioExecutor.execute(() -> {
			try {
				InputStream is = messagingManager.getFile(header);
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

	@UiThread
	LiveData<SendState> sendMessage(@Nullable String text,
			List<AttachmentHeader> headers, long expectedTimer) {
		MutableLiveData<SendState> liveData = new MutableLiveData<>();
		runOnDbThread(() -> {
			try {
				db.transaction(false, txn -> {
					long start = now();
					PrivateMessage m = createMessage(txn, text, headers,
							expectedTimer);
					messagingManager.addLocalMessage(txn, m);
					logDuration(LOG, "Storing message", start);
					Message message = m.getMessage();
					PrivateMessageHeader h = new PrivateMessageHeader(
							message.getId(), message.getGroupId(),
							message.getTimestamp(), true, true, false, false,
							m.hasText(), m.getAttachmentHeaders(),
							m.getAutoDeleteTimer());
					// TODO add text to cache when available here
					MessageId id = message.getId();
					txn.attach(() -> {
						attachmentCreator.onAttachmentsSent(id);
						liveData.setValue(SENT);
						addedHeader.setEvent(h);
					});
				});
			} catch (UnexpectedTimerException e) {
				liveData.postValue(UNEXPECTED_TIMER);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				liveData.postValue(ERROR);
			}
		});
		return liveData;
	}

	private PrivateMessage createMessage(Transaction txn, @Nullable String text,
			List<AttachmentHeader> headers, long expectedTimer)
			throws DbException {
		// Sending is only possible (setReady(true)) after loading all messages
		// which happens after the contact has been loaded.
		// privateMessageFormat is loaded together with contact
		Contact contact = requireNonNull(contactItem.getValue()).getContact();
		GroupId groupId = messagingManager.getContactGroup(contact).getId();
		PrivateMessageFormat format =
				requireNonNull(privateMessageFormat.getValue());
		long timestamp = conversationManager
				.getTimestampForOutgoingMessage(txn, requireNonNull(contactId));
		try {
			if (format == TEXT_ONLY) {
				return privateMessageFactory.createLegacyPrivateMessage(
						groupId, timestamp, requireNonNull(text));
			} else if (format == TEXT_IMAGES) {
				return privateMessageFactory.createPrivateMessage(groupId,
						timestamp, text, headers);
			} else {
				long timer = autoDeleteManager
						.getAutoDeleteTimer(txn, contactId, timestamp);
				if (timer != expectedTimer)
					throw new UnexpectedTimerException();
				return privateMessageFactory.createPrivateMessage(groupId,
						timestamp, text, headers, timer);
			}
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}

	void setAutoDeleteTimerEnabled(boolean enabled) {
		long timer = enabled ? DEFAULT_TIMER_DURATION : NO_AUTO_DELETE_TIMER;
		// ContactId is set before menu gets inflated and UI interaction
		final ContactId c = requireNonNull(contactId);
		runOnDbThread(() -> {
			try {
				db.transaction(false, txn ->
						autoDeleteManager.setAutoDeleteTimer(txn, c, timer));
				autoDeleteTimer.postValue(timer);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	AttachmentRetriever getAttachmentRetriever() {
		return attachmentRetriever;
	}

	LiveData<ContactItem> getContactItem() {
		return contactItem;
	}

	LiveData<String> getContactDisplayName() {
		return contactName;
	}

	LiveData<PrivateMessageFormat> getPrivateMessageFormat() {
		return privateMessageFormat;
	}

	LiveEvent<Boolean> showImageOnboarding() {
		return showImageOnboarding;
	}

	LiveEvent<Boolean> showIntroductionOnboarding() {
		return showIntroductionOnboarding;
	}

	LiveData<Boolean> showIntroductionAction() {
		return showIntroductionAction;
	}

	LiveData<Long> getAutoDeleteTimer() {
		return autoDeleteTimer;
	}

	LiveData<Boolean> isContactDeleted() {
		return contactDeleted;
	}

	LiveEvent<PrivateMessageHeader> getAddedPrivateMessage() {
		return addedHeader;
	}

	@UiThread
	void recheckFeaturesAndOnboarding(ContactId contactId) {
		runOnDbThread(() -> {
			try {
				checkFeaturesAndOnboarding(contactId);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}
}
