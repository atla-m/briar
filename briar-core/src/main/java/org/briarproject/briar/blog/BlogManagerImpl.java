package org.briarproject.briar.blog;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.BdfIncomingMessageHook;
import org.briarproject.bramble.api.cleanup.CleanupHook;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactManager.ContactHook;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogCommentHeader;
import org.briarproject.briar.api.blog.BlogFactory;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogPost;
import org.briarproject.briar.api.blog.BlogPostFactory;
import org.briarproject.briar.api.blog.BlogPostHeader;
import org.briarproject.briar.api.blog.MessageType;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.briar.attachment.ChunkedFileStore;
import org.briarproject.briar.api.blog.event.BlogAttachmentReceivedEvent;
import org.briarproject.briar.api.blog.event.BlogPostAddedEvent;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_SHARE;
import static java.util.Collections.emptyList;
import static org.briarproject.briar.api.blog.BlogConstants.GROUP_KEY_LATEST_TIMESTAMP;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_AUTHOR;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_COMMENT;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_ORIGINAL_MSG_ID;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_ORIGINAL_PARENT_MSG_ID;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_PARENT_MSG_ID;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_READ;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_RSS_FEED;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_TIMESTAMP;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_TIME_RECEIVED;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_TYPE;
import static org.briarproject.briar.api.blog.BlogConstants.KEY_WRAPPED_ATTACHMENTS;
import static org.briarproject.briar.api.blog.MessageType.COMMENT;
import static org.briarproject.briar.api.blog.BlogConstants.MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.briarproject.briar.api.blog.MessageType.ATTACHMENT;
import static org.briarproject.briar.api.blog.MessageType.FILE_CHUNK;
import static org.briarproject.briar.api.blog.MessageType.FILE_MANIFEST;
import static org.briarproject.briar.api.blog.MessageType.FILE_REQUEST;
import static org.briarproject.briar.api.blog.MessageType.POST;
import static org.briarproject.briar.api.blog.MessageType.WRAPPED_COMMENT;
import static org.briarproject.briar.api.blog.MessageType.WRAPPED_POST;
import static org.briarproject.briar.api.identity.AuthorInfo.Status.NONE;

@NotNullByDefault
class BlogManagerImpl extends BdfIncomingMessageHook implements BlogManager,
		OpenDatabaseHook, ContactHook, CleanupHook {

	private final IdentityManager identityManager;
	private final AuthorManager authorManager;
	private final BlogFactory blogFactory;
	private final BlogPostFactory blogPostFactory;
	private final BlogFileClient fileClient;
	private final ChunkedFileStore fileStore;
	private final List<RemoveBlogHook> removeHooks;

	@Inject
	BlogManagerImpl(DatabaseComponent db, IdentityManager identityManager,
			AuthorManager authorManager, ClientHelper clientHelper,
			MetadataParser metadataParser, BlogFactory blogFactory,
			BlogPostFactory blogPostFactory, CryptoComponent crypto,
			DatabaseConfig databaseConfig) {
		super(db, clientHelper, metadataParser);
		this.identityManager = identityManager;
		this.authorManager = authorManager;
		this.blogFactory = blogFactory;
		this.blogPostFactory = blogPostFactory;
		fileClient = new BlogFileClient(clientHelper);
		fileStore = new ChunkedFileStore(db, clientHelper, crypto, fileClient,
				databaseConfig);
		removeHooks = new CopyOnWriteArrayList<>();
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		// Create our personal blog if necessary
		LocalAuthor a = identityManager.getLocalAuthor(txn);
		Blog b = blogFactory.createBlog(a);
		db.addGroup(txn, b.getGroup());  // does nothing, if group exists
	}

	@Override
	public void addingContact(Transaction txn, Contact c) {
	}

	@Override
	public void removingContact(Transaction txn, Contact c) throws DbException {
		Blog b = blogFactory.createBlog(c.getAuthor());
		// TODO we might want to reconsider removing b, if otherwise shared
		if (db.containsGroup(txn, b.getId())) removeBlog(txn, b);
	}

	@Override
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		// An image or file chunk is a BDF list followed by raw bytes, so
		// the superclass can't parse the body as a list. Handle it here.
		try {
			BdfDictionary metaDict = metadataParser.parse(meta);
			MessageType type = getMessageType(metaDict);
			if (type == ATTACHMENT) {
				handleAttachment(txn, m);
				return ACCEPT_SHARE;
			} else if (type == FILE_CHUNK) {
				fileStore.incomingChunk(txn, m, metaDict);
				return ACCEPT_SHARE;
			}
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
		return super.incomingMessage(txn, m, meta);
	}

	@Override
	protected DeliveryAction incomingMessage(Transaction txn, Message m,
			BdfList list, BdfDictionary meta)
			throws DbException, FormatException {

		GroupId groupId = m.getGroupId();
		MessageType type = getMessageType(meta);

		if (type == FILE_MANIFEST) {
			fileStore.incomingManifest(txn, m, meta);
			return ACCEPT_SHARE;
		}

		if (type == FILE_REQUEST) {
			// Shared onwards, so it reaches whoever holds the file
			fileStore.incomingRequest(txn, m, meta);
			return ACCEPT_SHARE;
		}

		if (type == POST || type == COMMENT) {
			BlogPostHeader h =
					getPostHeaderFromMetadata(txn, groupId, m.getId(), meta);

			// check that original message IDs match
			if (type == COMMENT) {
				MessageId parentId = h.getParentId();
				if (parentId == null) throw new FormatException();
				BdfDictionary d = clientHelper
						.getMessageMetadataAsDictionary(txn, parentId);
				byte[] original1 = d.getRaw(KEY_ORIGINAL_MSG_ID);
				byte[] original2 = meta.getRaw(KEY_ORIGINAL_PARENT_MSG_ID);
				if (!Arrays.equals(original1, original2)) {
					throw new FormatException();
				}
			}

			// The images and files this post references are wanted now,
			// so they aren't cleaned up while they're still arriving
			if (type == POST) {
				fileStore.onFilesReferenced(txn, groupId,
						fileClient.getReferencedIds(meta));
				stopAttachmentCleanupTimers(txn, groupId, meta);
			}

			// broadcast event about new post or comment
			BlogPostAddedEvent event =
					new BlogPostAddedEvent(groupId, h, false);
			txn.attach(event);

			// shares message and its dependencies
			return ACCEPT_SHARE;
		} else if (type == WRAPPED_COMMENT) {
			// Check that the original message ID in the dependency's metadata
			// matches the original parent ID of the wrapped comment
			MessageId dependencyId =
					new MessageId(meta.getRaw(KEY_PARENT_MSG_ID));
			BdfDictionary d = clientHelper
					.getMessageMetadataAsDictionary(txn, dependencyId);
			byte[] original1 = d.getRaw(KEY_ORIGINAL_MSG_ID);
			byte[] original2 = meta.getRaw(KEY_ORIGINAL_PARENT_MSG_ID);
			if (!Arrays.equals(original1, original2)) {
				throw new FormatException();
			}
		}
		// don't share message until parent arrives
		return ACCEPT_DO_NOT_SHARE;
	}

	/**
	 * Gives an image that has just arrived a deadline, unless a post
	 * already references it, so images that no post ever references don't
	 * pile up.
	 */
	@Override
	public void deleteMessages(Transaction txn, GroupId g,
			Collection<MessageId> messageIds) throws DbException {
		// Only attachments, manifests and chunks carry cleanup timers
		fileStore.deleteExpired(txn, g, messageIds);
	}

	private void handleAttachment(Transaction txn, Message m)
			throws DbException, FormatException {
		txn.attach(new BlogAttachmentReceivedEvent(m.getGroupId(), m.getId()));
		if (!fileClient.isManifestReferenced(txn, m.getGroupId(), m.getId())) {
			db.setCleanupTimerDuration(txn, m.getId(),
					MISSING_ATTACHMENT_CLEANUP_DURATION_MS);
			db.startCleanupTimer(txn, m.getId());
		}
	}

	/**
	 * Stops the deadlines of the images a delivered post references, which
	 * are wanted now.
	 */
	private void stopAttachmentCleanupTimers(Transaction txn, GroupId g,
			BdfDictionary postMeta) throws DbException, FormatException {
		Set<MessageId> referenced = fileClient.getReferencedIds(postMeta);
		if (referenced.isEmpty()) return;
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(KEY_TYPE, ATTACHMENT.getInt()));
		Collection<MessageId> present =
				clientHelper.getMessageIds(txn, g, query);
		for (MessageId id : referenced) {
			if (present.contains(id)) db.stopCleanupTimer(txn, id);
		}
	}

	@Override
	public long getNextTimestamp(GroupId g, long earliest)
			throws DbException {
		return db.transactionWithResult(true,
				txn -> getNextTimestamp(txn, g, earliest));
	}

	@Override
	public long getNextTimestamp(Transaction txn, GroupId g, long earliest)
			throws DbException {
		// Only a channel is published as a file whose order matters. A
		// personal blog syncs message by message, and an RSS post keeps
		// the date its feed gave it.
		if (!getBlog(txn, g).isChannel()) return earliest;
		try {
			BdfDictionary meta =
					clientHelper.getGroupMetadataAsDictionary(txn, g);
			long latest = meta.getLong(GROUP_KEY_LATEST_TIMESTAMP, 0L);
			return Math.max(earliest, latest + 1);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	/**
	 * Records the timestamp of a message we have just added, so the next
	 * one we add is timestamped after it.
	 */
	private void recordTimestamp(Transaction txn, GroupId g, long timestamp)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getGroupMetadataAsDictionary(txn, g);
			long latest = meta.getLong(GROUP_KEY_LATEST_TIMESTAMP, 0L);
			if (timestamp <= latest) return;
			clientHelper.mergeGroupMetadata(txn, g, BdfDictionary.of(
					new BdfEntry(GROUP_KEY_LATEST_TIMESTAMP, timestamp)));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public AttachmentHeader addLocalAttachment(GroupId groupId,
			long earliest, String contentType, InputStream in)
			throws DbException, IOException {
		// After everything already in this blog, so publishing again only
		// adds to the end of the published file
		long timestamp = getNextTimestamp(groupId, earliest);
		// An image is a BDF descriptor followed by the raw bytes, and the
		// whole thing must fit into a single message
		ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
		byte[] descriptor = clientHelper.toByteArray(
				BdfList.of(ATTACHMENT.getInt(), contentType));
		bodyOut.write(descriptor);
		copyAndClose(in, bodyOut);
		if (bodyOut.size() > MAX_MESSAGE_BODY_LENGTH)
			throw new FileTooBigException();
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_TYPE, ATTACHMENT.getInt());
		meta.put(KEY_TIMESTAMP, timestamp);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptor.length);
		Message m = clientHelper.createMessage(groupId, timestamp,
				bodyOut.toByteArray());
		// Not shared until the post that references it is added
		db.transaction(false, txn -> {
			clientHelper.addLocalMessage(txn, m, meta, false, true);
			if (getBlog(txn, groupId).isChannel()) {
				recordTimestamp(txn, groupId, timestamp);
			}
		});
		return new AttachmentHeader(groupId, m.getId(), contentType);
	}

	@Override
	public FileHeader addLocalFile(GroupId groupId, long earliest,
			String name, String contentType, StreamSource source)
			throws DbException, IOException {
		long timestamp = getNextTimestamp(groupId, earliest);
		FileHeader header = fileStore.addLocalFile(groupId, timestamp, name,
				contentType, source);
		// The chunks take the timestamp after the manifest's
		db.transaction(false, txn -> {
			if (getBlog(txn, groupId).isChannel()) {
				recordTimestamp(txn, groupId, timestamp + 1);
			}
		});
		return header;
	}

	@Override
	public FileHeader getFileHeader(GroupId groupId, MessageId manifestId)
			throws DbException {
		return fileStore.getFileHeader(groupId, manifestId);
	}

	@Override
	public FileStatus getFileStatus(FileHeader header) throws DbException {
		return fileStore.getFileStatus(header);
	}

	@Override
	public void requestFile(FileHeader header) throws DbException {
		db.transaction(false, txn -> fileStore.requestFile(txn, header));
	}

	@Override
	public InputStream getFile(FileHeader header) throws DbException {
		return fileStore.getFile(header);
	}

	@Override
	public byte[] getFileChunk(FileHeader header, int index)
			throws DbException {
		return fileStore.getFileChunk(header, index);
	}

	@Override
	public void removeAttachment(AttachmentHeader header) throws DbException {
		db.transaction(false, txn ->
				db.removeMessage(txn, header.getMessageId()));
	}

	@Override
	public boolean isChunkOf(Transaction txn, MessageId manifestId, int index,
			byte[] body, int descriptorLength) throws DbException {
		return fileStore.isChunkOf(txn, manifestId, index, body,
				descriptorLength);
	}

	@Override
	public void removeFile(FileHeader header) throws DbException {
		fileStore.removeFile(header);
	}

	@Override
	public void addBlog(Blog b) throws DbException {
		Transaction txn = db.startTransaction(false);
		try {
			db.addGroup(txn, b.getGroup());
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public void addBlog(Transaction txn, Blog b) throws DbException {
		db.addGroup(txn, b.getGroup());
	}

	@Override
	public boolean canBeRemoved(Blog b) throws DbException {
		Transaction txn = db.startTransaction(true);
		try {
			boolean canBeRemoved = canBeRemoved(txn, b);
			db.commitTransaction(txn);
			return canBeRemoved;
		} finally {
			db.endTransaction(txn);
		}
	}

	private boolean canBeRemoved(Transaction txn, Blog b)
			throws DbException {
		AuthorId authorId = b.getAuthor().getId();
		LocalAuthor localAuthor = identityManager.getLocalAuthor(txn);
		return !localAuthor.getId().equals(authorId);
	}

	@Override
	public void removeBlog(Blog b) throws DbException {
		Transaction txn = db.startTransaction(false);
		try {
			removeBlog(txn, b);
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public void removeBlog(Transaction txn, Blog b) throws DbException {
		if (!canBeRemoved(txn, b))
			throw new IllegalArgumentException();
		for (RemoveBlogHook hook : removeHooks)
			hook.removingBlog(txn, b);
		db.removeGroup(txn, b.getGroup());
	}

	@Override
	public void addLocalPost(BlogPost p) throws DbException {
		Transaction txn = db.startTransaction(false);
		try {
			addLocalPost(txn, p);
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public void addLocalPost(Transaction txn, BlogPost p) throws DbException {
		try {
			GroupId groupId = p.getMessage().getGroupId();
			Blog b = getBlog(txn, groupId);

			BdfDictionary meta = new BdfDictionary();
			meta.put(KEY_TYPE, POST.getInt());
			meta.put(KEY_TIMESTAMP, p.getMessage().getTimestamp());
			meta.put(KEY_AUTHOR, clientHelper.toList(p.getAuthor()));
			meta.put(KEY_READ, true);
			meta.put(KEY_RSS_FEED, b.isRssFeed());
			List<AttachmentHeader> attachments = p.getAttachmentHeaders();
			List<FileHeader> files = p.getFileHeaders();
			if (!attachments.isEmpty() || !files.isEmpty()) {
				meta.put(KEY_ATTACHMENT_HEADERS,
						encodeAttachmentHeaders(attachments, files));
				shareAttachments(txn, groupId, attachments, files);
			}
			clientHelper.addLocalMessage(txn, p.getMessage(), meta, true,
					false);
			if (b.isChannel()) {
				recordTimestamp(txn, groupId,
						p.getMessage().getTimestamp());
			}

			// broadcast event about new post
			MessageId postId = p.getMessage().getId();
			BlogPostHeader h =
					getPostHeaderFromMetadata(txn, groupId, postId, meta);
			boolean local = !b.isRssFeed();
			BlogPostAddedEvent event =
					new BlogPostAddedEvent(groupId, h, local);
			txn.attach(event);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void addLocalComment(LocalAuthor author, GroupId groupId,
			@Nullable String comment, BlogPostHeader parentHeader)
			throws DbException {
		db.transaction(false, txn -> {
			addLocalComment(txn, author, groupId, comment, parentHeader);
		});
	}

	@Override
	public void addLocalComment(Transaction txn, LocalAuthor author,
			GroupId groupId, @Nullable String comment,
			BlogPostHeader parentHeader) throws DbException {
		MessageType type = parentHeader.getType();
		if (type != POST && type != COMMENT)
			throw new IllegalArgumentException("Comment on unknown type!");

		try {
			// Wrap post that we are commenting on
			MessageId parentOriginalId =
					getOriginalMessageId(txn, parentHeader);
			MessageId parentCurrentId =
					wrapMessage(txn, groupId, parentHeader, parentOriginalId);

			// Create actual comment
			Message message = blogPostFactory.createBlogComment(groupId, author,
					comment, parentOriginalId, parentCurrentId);
			BdfDictionary meta = new BdfDictionary();
			meta.put(KEY_TYPE, COMMENT.getInt());
			if (comment != null) meta.put(KEY_COMMENT, comment);
			meta.put(KEY_TIMESTAMP, message.getTimestamp());
			meta.put(KEY_ORIGINAL_MSG_ID, message.getId());
			meta.put(KEY_ORIGINAL_PARENT_MSG_ID, parentOriginalId);
			meta.put(KEY_PARENT_MSG_ID, parentCurrentId);
			meta.put(KEY_AUTHOR, clientHelper.toList(author));
			meta.put(KEY_READ, true);

			// Send comment
			clientHelper.addLocalMessage(txn, message, meta, true, false);

			// broadcast event
			BlogPostHeader h = getPostHeaderFromMetadata(txn, groupId,
					message.getId(), meta);
			BlogPostAddedEvent event = new BlogPostAddedEvent(groupId, h, true);
			txn.attach(event);
		} catch (FormatException e) {
			throw new DbException(e);
		} catch (GeneralSecurityException e) {
			throw new IllegalArgumentException("Invalid key of author", e);
		}
	}

	private MessageId getOriginalMessageId(Transaction txn, BlogPostHeader h)
			throws DbException, FormatException {
		MessageType type = h.getType();
		if (type == POST || type == COMMENT) return h.getId();
		BdfDictionary meta = clientHelper.getMessageMetadataAsDictionary(txn,
				h.getId());
		return new MessageId(meta.getRaw(KEY_ORIGINAL_MSG_ID));
	}

	private MessageId wrapMessage(Transaction txn, GroupId groupId,
			BlogPostHeader header, MessageId originalId)
			throws DbException, FormatException {

		if (groupId.equals(header.getGroupId())) {
			// We are trying to wrap a post that is already in our group.
			// This is unnecessary, so just return the post's MessageId
			return header.getId();
		}

		// Get body of message to be wrapped
		BdfList body = clientHelper.getMessageAsList(txn, header.getId());
		long timestamp = header.getTimestamp();
		Message wrappedMessage;

		BdfDictionary meta = new BdfDictionary();
		MessageType type = header.getType();
		if (type == POST) {
			// Wrap post
			Group group = db.getGroup(txn, header.getGroupId());
			byte[] descriptor = group.getDescriptor();
			wrappedMessage = blogPostFactory.wrapPost(groupId, descriptor,
					timestamp, body);
			meta.put(KEY_TYPE, WRAPPED_POST.getInt());
			meta.put(KEY_RSS_FEED, header.isRssFeed());
			// The copy carries the headers of the original's images and
			// files, but not the images and files themselves
			int attachments = header.getAttachmentHeaders().size() +
					header.getFileHeaders().size();
			if (attachments > 0) meta.put(KEY_WRAPPED_ATTACHMENTS, attachments);
		} else if (type == COMMENT) {
			// Recursively wrap parent
			BlogCommentHeader commentHeader = (BlogCommentHeader) header;
			BlogPostHeader parentHeader = commentHeader.getParent();
			MessageId parentOriginalId =
					getOriginalMessageId(txn, parentHeader);
			MessageId parentCurrentId =
					wrapMessage(txn, groupId, parentHeader, parentOriginalId);
			// Wrap comment
			Group group = db.getGroup(txn, header.getGroupId());
			byte[] descriptor = group.getDescriptor();
			wrappedMessage = blogPostFactory.wrapComment(groupId, descriptor,
					timestamp, body, parentCurrentId);
			meta.put(KEY_TYPE, WRAPPED_COMMENT.getInt());
			if (commentHeader.getComment() != null)
				meta.put(KEY_COMMENT, commentHeader.getComment());
			meta.put(KEY_PARENT_MSG_ID, parentCurrentId);
		} else if (type == WRAPPED_POST) {
			// Re-wrap wrapped post without adding another wrapping layer
			wrappedMessage = blogPostFactory.rewrapWrappedPost(groupId, body);
			meta.put(KEY_TYPE, WRAPPED_POST.getInt());
			meta.put(KEY_RSS_FEED, header.isRssFeed());
			if (header.getAttachmentsNotCarried() > 0) {
				meta.put(KEY_WRAPPED_ATTACHMENTS,
						header.getAttachmentsNotCarried());
			}
		} else if (type == WRAPPED_COMMENT) {
			// Recursively wrap parent
			BlogCommentHeader commentHeader = (BlogCommentHeader) header;
			BlogPostHeader parentHeader = commentHeader.getParent();
			MessageId parentOriginalId =
					getOriginalMessageId(txn, parentHeader);
			MessageId parentCurrentId =
					wrapMessage(txn, groupId, parentHeader, parentOriginalId);
			// Re-wrap wrapped comment
			wrappedMessage = blogPostFactory.rewrapWrappedComment(groupId, body,
					parentCurrentId);
			meta.put(KEY_TYPE, WRAPPED_COMMENT.getInt());
			if (commentHeader.getComment() != null)
				meta.put(KEY_COMMENT, commentHeader.getComment());
			meta.put(KEY_PARENT_MSG_ID, parentCurrentId);
		} else {
			throw new IllegalArgumentException(
					"Unknown Message Type: " + type);
		}
		meta.put(KEY_ORIGINAL_MSG_ID, originalId);
		meta.put(KEY_AUTHOR, clientHelper.toList(header.getAuthor()));
		meta.put(KEY_TIMESTAMP, header.getTimestamp());
		meta.put(KEY_TIME_RECEIVED, header.getTimeReceived());

		// Send wrapped message and store metadata
		clientHelper.addLocalMessage(txn, wrappedMessage, meta, true, false);
		return wrappedMessage.getId();
	}

	@Override
	public Blog getBlog(GroupId g) throws DbException {
		Blog blog;
		Transaction txn = db.startTransaction(true);
		try {
			blog = getBlog(txn, g);
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
		return blog;
	}

	@Override
	public Blog getBlog(Transaction txn, GroupId g) throws DbException {
		try {
			Group group = db.getGroup(txn, g);
			return blogFactory.parseBlog(group);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public Collection<Blog> getBlogs(LocalAuthor localAuthor)
			throws DbException {

		Collection<Blog> allBlogs = getBlogs();
		List<Blog> blogs = new ArrayList<>();
		for (Blog b : allBlogs) {
			if (b.getAuthor().equals(localAuthor)) {
				blogs.add(b);
			}
		}
		return blogs;
	}

	@Override
	public Blog getPersonalBlog(Author author) {
		return blogFactory.createBlog(author);
	}

	@Override
	public Collection<Blog> getBlogs() throws DbException {
		return db.transactionWithResult(true, this::getBlogs);
	}

	@Override
	public Collection<Blog> getBlogs(Transaction txn) throws DbException {
		try {
			List<Blog> blogs = new ArrayList<>();
			Collection<Group> groups =
					db.getGroups(txn, CLIENT_ID, MAJOR_VERSION);
			for (Group g : groups) {
				blogs.add(blogFactory.parseBlog(g));
			}
			return blogs;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public Collection<GroupId> getBlogIds(Transaction txn) throws DbException {
		List<GroupId> groupIds = new ArrayList<>();
		Collection<Group> groups = db.getGroups(txn, CLIENT_ID, MAJOR_VERSION);
		for (Group g : groups) groupIds.add(g.getId());
		return groupIds;
	}

	@Override
	public BlogPostHeader getPostHeader(Transaction txn, GroupId g, MessageId m)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, m);
			return getPostHeaderFromMetadata(txn, g, m, meta);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public String getPostText(MessageId m) throws DbException {
		try {
			return getPostText(clientHelper.getMessageAsList(m));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public String getPostText(Transaction txn, MessageId m) throws DbException {
		try {
			return getPostText(clientHelper.getMessageAsList(txn, m));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private String getPostText(BdfList message) throws FormatException {
		MessageType type = MessageType.valueOf(message.getInt(0));
		if (type == POST) {
			// Type, text, signature, or type, text, headers, signature.
			// A post with attachments may have no text
			String text = message.getOptionalString(1);
			return text == null ? "" : text;
		} else if (type == WRAPPED_POST) {
			// Type, copied group descriptor, copied timestamp, copied text,
			// then the copied headers if the original had attachments, then
			// the copied signature
			String text = message.getOptionalString(3);
			return text == null ? "" : text;
		} else {
			throw new FormatException();
		}
	}

	@Override
	public Collection<BlogPostHeader> getPostHeaders(GroupId g)
			throws DbException {
		return db.transactionWithResult(true, txn -> getPostHeaders(txn, g));
	}

	@Override
	public List<BlogPostHeader> getPostHeaders(Transaction txn, GroupId g)
			throws DbException {
		// Query for posts and comments only
		BdfDictionary query1 = BdfDictionary.of(
				new BdfEntry(KEY_TYPE, POST.getInt())
		);
		BdfDictionary query2 = BdfDictionary.of(
				new BdfEntry(KEY_TYPE, COMMENT.getInt())
		);

		List<BlogPostHeader> headers = new ArrayList<>();
		try {
			Map<MessageId, BdfDictionary> metadata1 =
					clientHelper.getMessageMetadataAsDictionary(txn, g, query1);
			Map<MessageId, BdfDictionary> metadata2 =
					clientHelper.getMessageMetadataAsDictionary(txn, g, query2);
			Map<MessageId, BdfDictionary> metadata =
					new HashMap<>(metadata1.size() + metadata2.size());
			metadata.putAll(metadata1);
			metadata.putAll(metadata2);
			// get all authors we need to get the information for
			Set<AuthorId> authors = new HashSet<>();
			for (Entry<MessageId, BdfDictionary> entry : metadata.entrySet()) {
				BdfList authorList = entry.getValue().getList(KEY_AUTHOR);
				Author a = clientHelper.parseAndValidateAuthor(authorList);
				authors.add(a.getId());
			}
			// get information for all authors
			Map<AuthorId, AuthorInfo> authorInfos = new HashMap<>();
			for (AuthorId authorId : authors) {
				authorInfos.put(authorId,
						authorManager.getAuthorInfo(txn, authorId));
			}
			// get post headers
			for (Entry<MessageId, BdfDictionary> entry : metadata.entrySet()) {
				BdfDictionary meta = entry.getValue();
				BlogPostHeader h = getPostHeaderFromMetadata(txn, g,
						entry.getKey(), meta, authorInfos);
				headers.add(h);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return headers;
	}

	@Override
	public void setReadFlag(MessageId m, boolean read) throws DbException {
		db.transaction(true, txn -> {
			setReadFlag(txn, m, read);
		});
	}

	@Override
	public void setReadFlag(Transaction txn, MessageId m, boolean read)
			throws DbException {
		try {
			BdfDictionary meta = new BdfDictionary();
			meta.put(KEY_READ, read);
			clientHelper.mergeMessageMetadata(txn, m, meta);
		} catch (FormatException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void registerRemoveBlogHook(RemoveBlogHook hook) {
		removeHooks.add(hook);
	}

	/**
	 * Encodes the headers of the images and files a post carries, in the
	 * same shape the post's signature covers: an image is a message ID and
	 * a content type, a file adds its name and size.
	 */
	private BdfList encodeAttachmentHeaders(List<AttachmentHeader> attachments,
			List<FileHeader> files) {
		BdfList headers = new BdfList();
		for (AttachmentHeader a : attachments) {
			headers.add(BdfList.of(a.getMessageId(), a.getContentType()));
		}
		for (FileHeader f : files) {
			headers.add(BdfList.of(f.getManifestId(), f.getContentType(),
					f.getName(), f.getSize()));
		}
		return headers;
	}

	/**
	 * Marks the images and files a local post references as shared and
	 * permanent, now that the post revealing them is being stored. An
	 * entry may point at a chunked image's manifest, in which case its
	 * chunks are shared too.
	 */
	private void shareAttachments(Transaction txn, GroupId groupId,
			List<AttachmentHeader> attachments, List<FileHeader> files)
			throws DbException {
		Set<MessageId> referenced = new HashSet<>();
		for (AttachmentHeader a : attachments) {
			referenced.add(a.getMessageId());
		}
		for (FileHeader f : files) referenced.add(f.getManifestId());
		for (MessageId id : referenced) {
			if (fileStore.isManifest(txn, id)) {
				fileStore.shareFile(txn, groupId, id);
			} else {
				db.setMessageShared(txn, id);
				db.setMessagePermanent(txn, id);
			}
		}
	}

	/**
	 * Parses the images a post carries, which are the two-element entries
	 * of its header list.
	 */
	private List<AttachmentHeader> parseAttachmentHeaders(GroupId g,
			BdfDictionary meta) throws FormatException {
		if (!meta.containsKey(KEY_ATTACHMENT_HEADERS)) return emptyList();
		BdfList list = meta.getList(KEY_ATTACHMENT_HEADERS);
		List<AttachmentHeader> headers = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			BdfList header = list.getList(i);
			if (header.size() != 2) continue;
			MessageId m = new MessageId(header.getRaw(0));
			headers.add(new AttachmentHeader(g, m, header.getString(1)));
		}
		return headers;
	}

	/**
	 * Parses the files a post shares, which are the four-element entries
	 * of its header list.
	 */
	private List<FileHeader> parseFileHeaders(GroupId g, BdfDictionary meta)
			throws FormatException {
		if (!meta.containsKey(KEY_ATTACHMENT_HEADERS)) return emptyList();
		BdfList list = meta.getList(KEY_ATTACHMENT_HEADERS);
		List<FileHeader> headers = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			BdfList header = list.getList(i);
			if (header.size() != 4) continue;
			MessageId manifestId = new MessageId(header.getRaw(0));
			headers.add(new FileHeader(g, manifestId, header.getString(2),
					header.getString(1), header.getLong(3)));
		}
		return headers;
	}

	private BlogPostHeader getPostHeaderFromMetadata(Transaction txn,
			GroupId groupId, MessageId id) throws DbException, FormatException {
		BdfDictionary meta =
				clientHelper.getMessageMetadataAsDictionary(txn, id);
		return getPostHeaderFromMetadata(txn, groupId, id, meta);
	}

	private BlogPostHeader getPostHeaderFromMetadata(Transaction txn,
			GroupId groupId, MessageId id, BdfDictionary meta)
			throws DbException, FormatException {
		return getPostHeaderFromMetadata(txn, groupId, id, meta,
				Collections.emptyMap());
	}

	private BlogPostHeader getPostHeaderFromMetadata(Transaction txn,
			GroupId groupId, MessageId id, BdfDictionary meta,
			Map<AuthorId, AuthorInfo> authorInfos)
			throws DbException, FormatException {

		MessageType type = getMessageType(meta);

		long timestamp = meta.getLong(KEY_TIMESTAMP);
		long timeReceived = meta.getLong(KEY_TIME_RECEIVED, timestamp);

		BdfList authorList = meta.getList(KEY_AUTHOR);
		Author author = clientHelper.parseAndValidateAuthor(authorList);
		boolean isFeedPost = meta.getBoolean(KEY_RSS_FEED, false);
		AuthorInfo authorInfo;
		if (isFeedPost) {
			authorInfo = new AuthorInfo(NONE);
		} else if (authorInfos.containsKey(author.getId())) {
			authorInfo = authorInfos.get(author.getId());
		} else {
			authorInfo = authorManager.getAuthorInfo(txn, author.getId());
		}

		boolean read = meta.getBoolean(KEY_READ, false);

		if (type == COMMENT || type == WRAPPED_COMMENT) {
			String comment = meta.getOptionalString(KEY_COMMENT);
			MessageId parentId = new MessageId(meta.getRaw(KEY_PARENT_MSG_ID));
			BlogPostHeader parent =
					getPostHeaderFromMetadata(txn, groupId, parentId);
			return new BlogCommentHeader(type, groupId, comment, parent, id,
					timestamp, timeReceived, author, authorInfo, read);
		} else {
			int notCarried = meta.getLong(KEY_WRAPPED_ATTACHMENTS, 0L)
					.intValue();
			return new BlogPostHeader(type, groupId, id, null, timestamp,
					timeReceived, author, authorInfo, isFeedPost, read,
					parseAttachmentHeaders(groupId, meta),
					parseFileHeaders(groupId, meta), notCarried);
		}
	}

	private MessageType getMessageType(BdfDictionary d) throws FormatException {
		return MessageType.valueOf(d.getInt(KEY_TYPE));
	}
}
