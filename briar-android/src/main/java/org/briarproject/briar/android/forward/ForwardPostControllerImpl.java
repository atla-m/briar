package org.briarproject.briar.android.forward;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.NoSuchContactException;
import org.briarproject.bramble.api.db.NoSuchGroupException;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.android.contactselection.ContactSelectorControllerImpl;
import org.briarproject.briar.android.controller.handler.ResultExceptionHandler;
import org.briarproject.briar.api.autodelete.AutoDeleteManager;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.messaging.ForwardedPost;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.briar.api.messaging.PrivateMessageFactory;
import org.briarproject.briar.api.sharing.SharingManager.SharingStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.NOT_SUPPORTED;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHAREABLE;

@Immutable
@NotNullByDefault
class ForwardPostControllerImpl extends ContactSelectorControllerImpl
		implements ForwardPostController {

	private static final Logger LOG =
			getLogger(ForwardPostControllerImpl.class.getName());

	private final ContactManager contacts;
	private final TransactionManager db;
	private final ChannelManager channelManager;
	private final MessagingManager messagingManager;
	private final PrivateMessageFactory privateMessageFactory;
	private final ConversationManager conversationManager;
	private final AutoDeleteManager autoDeleteManager;

	@Inject
	ForwardPostControllerImpl(@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, ContactManager contactManager,
			AuthorManager authorManager, TransactionManager db,
			ChannelManager channelManager,
			MessagingManager messagingManager,
			PrivateMessageFactory privateMessageFactory,
			ConversationManager conversationManager,
			AutoDeleteManager autoDeleteManager) {
		super(dbExecutor, lifecycleManager, contactManager, authorManager);
		this.contacts = contactManager;
		this.db = db;
		this.channelManager = channelManager;
		this.messagingManager = messagingManager;
		this.privateMessageFactory = privateMessageFactory;
		this.conversationManager = conversationManager;
		this.autoDeleteManager = autoDeleteManager;
	}

	@Override
	protected SharingStatus getSharingStatus(GroupId g, Contact c)
			throws DbException {
		// A forward is an ordinary private message carrying the channel's
		// link, so the only question is whether the contact's messaging
		// client understands that format. Unlike sharing the channel
		// itself, forwarding the same post twice is allowed: it is a
		// message, not a subscription
		return db.transactionWithResult(true, txn ->
				messagingManager.getContactMessageFormat(txn, c.getId())
						.supportsForwarding() ? SHAREABLE : NOT_SUPPORTED);
	}

	@Override
	public void forward(GroupId blogId, MessageId postId,
			Collection<ContactId> contacts,
			ResultExceptionHandler<Void, DbException> handler) {
		runOnDbThread(() -> {
			try {
				// The post goes as the channel signed it, with the
				// channel's link, so the recipient's device can check the
				// text is the channel's and not ours. Read outside the
				// transaction below: it opens one of its own
				ForwardedPost post =
						channelManager.getForwardedPost(blogId, postId);
				db.transaction(false, txn -> {
					int sent = 0;
					for (ContactId c : contacts) {
						try {
							forward(txn, c, post);
							sent++;
						} catch (NoSuchContactException
								| NoSuchGroupException e) {
							logException(LOG, WARNING, e);
						}
					}
					// Success means someone got it
					if (sent == 0) throw new NoSuchContactException();
				});
				handler.onResult(null);
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				handler.onException(e);
			}
		});
	}

	private void forward(Transaction txn, ContactId c, ForwardedPost post)
			throws DbException {
		Contact contact = contacts.getContact(txn, c);
		GroupId g = messagingManager.getContactGroup(contact).getId();
		long timestamp =
				conversationManager.getTimestampForOutgoingMessage(txn, c);
		long timer = autoDeleteManager.getAutoDeleteTimer(txn, c, timestamp);
		PrivateMessage m;
		try {
			m = privateMessageFactory.createForwardedPost(g, timestamp, timer,
					post);
		} catch (FormatException | IllegalArgumentException e) {
			// A post near the size limit, with its link and signature,
			// can be too long for a private message
			throw new DbException(e);
		}
		messagingManager.addLocalMessage(txn, m);
	}
}
