package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.Bytes;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager.ContactHook;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Group.Visibility;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.validation.IncomingMessageHook;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.api.versioning.ClientVersioningManager.ClientVersioningHook;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogSharingManager;
import org.briarproject.briar.api.sharing.SharingManager.SharingStatus;
import org.briarproject.briar.api.client.ProtocolStateException;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.sync.Group.Visibility.INVISIBLE;
import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHAREABLE;
import static org.briarproject.briar.channel.ChannelConstants.GROUP_KEY_SHARE_WITH_CONTACTS;

/**
 * Tells each contact, privately, which channels we pass posts of, so a
 * channel is made visible to a contact only when both of us have turned
 * sharing on for it.
 * <p>
 * Making a channel visible to a contact who doesn't share it back would
 * not work: their phone requests every post it is offered, drops it
 * without an ack because the channel isn't visible to us from their side,
 * and the request resets our retransmission backoff, so the whole channel
 * would be sent again every couple of minutes for as long as we are
 * connected.
 * <p>
 * Each of us keeps one message in the group we share with the contact: a
 * list of tokens, one for each channel we pass posts of. A token is a hash
 * of the channel's group ID and the contact group's ID, so a contact who
 * doesn't hold the channel can't tell which channel it stands for unless
 * they hold its link, and the same channel gives a different token for
 * every contact, so tokens can't be matched across contacts. Nothing is
 * shown in any chat.
 * <p>
 * A channel shared with a contact through the blog sharing protocol
 * belongs to that protocol, which sets its visibility itself, so it is
 * left alone.
 */
@Immutable
@Singleton
@NotNullByDefault
class ChannelContactSharing implements OpenDatabaseHook, ContactHook,
		ClientVersioningHook, IncomingMessageHook {

	static final ClientId CLIENT_ID =
			new ClientId("org.briarproject.briar.channel.sharing");
	static final int MAJOR_VERSION = 0;
	static final int MINOR_VERSION = 0;

	/**
	 * The most channels one message can name. Tokens are 32 bytes, so this
	 * keeps a message well inside the largest body.
	 */
	static final int MAX_TOKENS = 500;

	private static final String TOKEN_LABEL =
			"org.briarproject.briar.channel.sharing/TOKEN";
	static final String MSG_KEY_LOCAL = "local";
	static final String MSG_KEY_VERSION = "version";

	private static final Logger LOG =
			getLogger(ChannelContactSharing.class.getName());

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final MetadataParser metadataParser;
	private final ContactGroupFactory contactGroupFactory;
	private final ClientVersioningManager clientVersioningManager;
	private final CryptoComponent crypto;
	private final BlogManager blogManager;
	private final BlogSharingManager blogSharingManager;
	private final Clock clock;

	@Inject
	ChannelContactSharing(DatabaseComponent db, ClientHelper clientHelper,
			MetadataParser metadataParser,
			ContactGroupFactory contactGroupFactory,
			ClientVersioningManager clientVersioningManager,
			CryptoComponent crypto, BlogManager blogManager,
			BlogSharingManager blogSharingManager, Clock clock) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.metadataParser = metadataParser;
		this.contactGroupFactory = contactGroupFactory;
		this.clientVersioningManager = clientVersioningManager;
		this.crypto = crypto;
		this.blogManager = blogManager;
		this.blogSharingManager = blogSharingManager;
		this.clock = clock;
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		// Contacts added before this client existed have no group yet.
		// Visibility set by an earlier build, which made a channel visible
		// to every contact, is brought into line here too
		for (Contact c : db.getContacts(txn)) {
			if (!db.containsGroup(txn, getContactGroup(c).getId())) {
				addingContact(txn, c);
			} else {
				update(txn, c);
			}
		}
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		Group g = getContactGroup(c);
		db.addGroup(txn, g);
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), CLIENT_ID, MAJOR_VERSION);
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);
		clientHelper.setContactId(txn, g.getId(), c.getId());
		update(txn, c);
	}

	@Override
	public void removingContact(Transaction txn, Contact c)
			throws DbException {
		db.removeGroup(txn, getContactGroup(c));
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Visibility v) throws DbException {
		db.setGroupVisibility(txn, c.getId(), getContactGroup(c).getId(), v);
	}

	@Override
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		try {
			// Keep only the contact's latest list
			BdfDictionary d = metadataParser.parse(meta);
			long version = d.getLong(MSG_KEY_VERSION);
			Latest latest = findLatest(txn, m.getGroupId(), false, m.getId());
			if (latest != null) {
				if (latest.version >= version) {
					deleteMessage(txn, m.getId());
					return ACCEPT_DO_NOT_SHARE;
				}
				deleteMessage(txn, latest.id);
			}
			ContactId c = clientHelper.getContactId(txn, m.getGroupId());
			applyVisibility(txn, db.getContact(txn, c), m.getGroupId(),
					parseTokens(clientHelper.toList(m)));
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
		return ACCEPT_DO_NOT_SHARE;
	}

	/**
	 * Sends every contact our current list and recomputes what is visible
	 * to them. Called when sharing is turned on or off for a channel, or a
	 * channel is added or removed.
	 */
	void updateAll(Transaction txn) throws DbException {
		for (Contact c : db.getContacts(txn)) update(txn, c);
	}

	/**
	 * Recomputes what is visible to one contact, for example after the
	 * sharing protocol has changed a channel's visibility.
	 */
	void reapply(Transaction txn, ContactId c) throws DbException {
		Contact contact = db.getContact(txn, c);
		GroupId g = getContactGroup(contact).getId();
		if (!db.containsGroup(txn, g)) return;
		applyVisibility(txn, contact, g, getTheirTokens(txn, g));
	}

	private void update(Transaction txn, Contact c) throws DbException {
		GroupId g = getContactGroup(c).getId();
		sendOurTokens(txn, g);
		applyVisibility(txn, c, g, getTheirTokens(txn, g));
	}

	/**
	 * Makes each channel we pass posts of visible to the contact if their
	 * list names it too, and hides it otherwise.
	 */
	private void applyVisibility(Transaction txn, Contact c, GroupId cg,
			Set<Bytes> theirs) throws DbException {
		for (Blog b : blogManager.getBlogs(txn)) {
			if (!b.isChannel()) continue;
			GroupId g = b.getId();
			if (!isOurs(txn, g, c)) continue;
			boolean on = isSharing(txn, g) && theirs.contains(token(cg, g));
			Visibility v = on ? SHARED : INVISIBLE;
			if (db.getGroupVisibility(txn, c.getId(), g) != v) {
				db.setGroupVisibility(txn, c.getId(), g, v);
			}
		}
	}

	/**
	 * Whether the channel's visibility to the contact is ours to set: it
	 * isn't if the sharing protocol has a session for it, and a session
	 * in a state the protocol can't report on is left alone too, so one
	 * contact can't stop the switch working for every other.
	 */
	private boolean isOurs(Transaction txn, GroupId g, Contact c)
			throws DbException {
		try {
			SharingStatus s = blogSharingManager.getSharingStatus(txn, g, c);
			return s == SHAREABLE;
		} catch (ProtocolStateException e) {
			if (LOG.isLoggable(INFO)) {
				LOG.info("Leaving a channel in a sharing session alone");
			}
			return false;
		}
	}

	private void sendOurTokens(Transaction txn, GroupId cg)
			throws DbException {
		BdfList tokens = new BdfList();
		for (Blog b : blogManager.getBlogs(txn)) {
			if (!b.isChannel() || !isSharing(txn, b.getId())) continue;
			if (tokens.size() == MAX_TOKENS) break;
			tokens.add(token(cg, b.getId()).getBytes());
		}
		try {
			Latest latest = findLatest(txn, cg, true, null);
			if (latest != null) {
				// Nothing to tell the contact if the list hasn't changed
				BdfList old = clientHelper.getMessageAsList(txn, latest.id);
				if (parseTokens(old).equals(parseTokens(
						BdfList.of(0L, tokens)))) {
					return;
				}
			} else if (tokens.isEmpty()) {
				// No list means none, so there's no need to send one
				return;
			}
			long version = latest == null ? 1 : latest.version + 1;
			// The timestamp only has to be valid; the version orders lists
			long now = clock.currentTimeMillis();
			Message m = clientHelper.createMessage(cg, now,
					BdfList.of(version, tokens));
			BdfDictionary meta = BdfDictionary.of(
					new BdfEntry(MSG_KEY_LOCAL, true),
					new BdfEntry(MSG_KEY_VERSION, version));
			clientHelper.addLocalMessage(txn, m, meta, true, false);
			if (latest != null) deleteMessage(txn, latest.id);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private Set<Bytes> getTheirTokens(Transaction txn, GroupId cg)
			throws DbException {
		try {
			Latest latest = findLatest(txn, cg, false, null);
			if (latest == null) return new HashSet<>();
			return parseTokens(clientHelper.getMessageAsList(txn, latest.id));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private static Set<Bytes> parseTokens(@Nullable BdfList body)
			throws FormatException {
		Set<Bytes> tokens = new HashSet<>();
		if (body == null) return tokens;
		BdfList list = body.getList(1);
		for (int i = 0; i < list.size(); i++) {
			tokens.add(new Bytes(list.getRaw(i)));
		}
		return tokens;
	}

	@Nullable
	private Latest findLatest(Transaction txn, GroupId cg, boolean local,
			@Nullable MessageId except) throws DbException, FormatException {
		Latest latest = null;
		Map<MessageId, BdfDictionary> all =
				clientHelper.getMessageMetadataAsDictionary(txn, cg);
		for (Entry<MessageId, BdfDictionary> e : all.entrySet()) {
			if (e.getKey().equals(except)) continue;
			BdfDictionary d = e.getValue();
			if (d.getBoolean(MSG_KEY_LOCAL, false) != local) continue;
			long version = d.getLong(MSG_KEY_VERSION, -1L);
			if (latest == null || version > latest.version) {
				latest = new Latest(e.getKey(), version);
			}
		}
		return latest;
	}

	private void deleteMessage(Transaction txn, MessageId m)
			throws DbException {
		db.deleteMessage(txn, m);
		db.deleteMessageMetadata(txn, m);
	}

	private boolean isSharing(Transaction txn, GroupId g) throws DbException {
		try {
			return clientHelper.getGroupMetadataAsDictionary(txn, g)
					.getBoolean(GROUP_KEY_SHARE_WITH_CONTACTS, false);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private Bytes token(GroupId contactGroup, GroupId channel) {
		return new Bytes(crypto.hash(TOKEN_LABEL, contactGroup.getBytes(),
				channel.getBytes()));
	}

	private Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(CLIENT_ID,
				MAJOR_VERSION, c);
	}

	private static class Latest {
		private final MessageId id;
		private final long version;

		private Latest(MessageId id, long version) {
			this.id = id;
			this.version = version;
		}
	}
}
