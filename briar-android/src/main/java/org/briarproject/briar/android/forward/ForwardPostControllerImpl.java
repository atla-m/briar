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
import org.briarproject.briar.android.controller.handler.ExceptionHandler;
import org.briarproject.briar.api.autodelete.AutoDeleteManager;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.identity.AuthorManager;
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

import static java.util.Collections.emptyList;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.android.util.UiUtils.getSpanned;
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
	private final BlogManager blogManager;
	private final ChannelManager channelManager;
	private final MessagingManager messagingManager;
	private final PrivateMessageFactory privateMessageFactory;
	private final ConversationManager conversationManager;
	private final AutoDeleteManager autoDeleteManager;

	@Inject
	ForwardPostControllerImpl(@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, ContactManager contactManager,
			AuthorManager authorManager, TransactionManager db,
			BlogManager blogManager, ChannelManager channelManager,
			MessagingManager messagingManager,
			PrivateMessageFactory privateMessageFactory,
			ConversationManager conversationManager,
			AutoDeleteManager autoDeleteManager) {
		super(dbExecutor, lifecycleManager, contactManager, authorManager);
		this.contacts = contactManager;
		this.db = db;
		this.blogManager = blogManager;
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
			ExceptionHandler<DbException> handler) {
		runOnDbThread(() -> {
			try {
				// Outside the transaction below: building a link opens a
				// transaction of its own, and they can't be nested
				String link = channelManager.getChannelLink(blogId);
				db.transaction(false, txn -> {
					// A blog post's text is HTML, a private message's is
					// not, so forward what a reader would have seen
					String text = getSpanned(
							blogManager.getPostText(txn, postId)).toString()
							.trim();
					for (ContactId c : contacts) {
						try {
							forward(txn, c, text, link);
						} catch (NoSuchContactException
								| NoSuchGroupException e) {
							logException(LOG, WARNING, e);
						}
					}
				});
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				handler.onException(e);
			}
		});
	}

	private void forward(Transaction txn, ContactId c, String text,
			String link) throws DbException {
		Contact contact = contacts.getContact(txn, c);
		GroupId g = messagingManager.getContactGroup(contact).getId();
		long timestamp =
				conversationManager.getTimestampForOutgoingMessage(txn, c);
		long timer = autoDeleteManager.getAutoDeleteTimer(txn, c, timestamp);
		PrivateMessage m;
		try {
			m = privateMessageFactory.createPrivateMessage(g, timestamp, text,
					emptyList(), emptyList(), timer, link);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		messagingManager.addLocalMessage(txn, m);
	}
}
