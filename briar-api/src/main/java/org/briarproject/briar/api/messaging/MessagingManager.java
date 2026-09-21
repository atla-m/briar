package org.briarproject.briar.api.messaging;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public interface MessagingManager extends ConversationClient {

	/**
	 * The unique ID of the messaging client.
	 */
	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.messaging");

	/**
	 * The current major version of the messaging client.
	 */
	int MAJOR_VERSION = 0;

	/**
	 * The current minor version of the messaging client.
	 */
	int MINOR_VERSION = 5;

	/**
	 * Stores a local private message.
	 */
	void addLocalMessage(PrivateMessage m) throws DbException;

	/**
	 * Stores a local private message.
	 */
	void addLocalMessage(Transaction txn, PrivateMessage m) throws DbException;

	/**
	 * Stores a local attachment message.
	 *
	 * @throws FileTooBigException If the attachment is too big
	 */
	AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream is) throws DbException, IOException;

	/**
	 * Removes an unsent attachment.
	 */
	void removeAttachment(AttachmentHeader header) throws DbException;

	/**
	 * Stores a local file for the given private conversation, split into
	 * chunks that each fit into a single message, plus a manifest listing
	 * the chunks. The file is not sent until a private message that
	 * references it is added with {@link #addLocalMessage(PrivateMessage)}.
	 * The contact must support
	 * {@link PrivateMessageFormat#TEXT_IMAGES_AUTO_DELETE_FILES}.
	 *
	 * @throws FileTooBigException If the file is larger than
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#MAX_FILE_SIZE}
	 */
	FileHeader addLocalFile(GroupId groupId, long timestamp, String name,
			String contentType, StreamSource source)
			throws DbException, IOException;

	/**
	 * Removes an unsent file and its chunks.
	 */
	void removeFile(FileHeader header) throws DbException;

	/**
	 * Returns how much of the given file has been received.
	 */
	FileStatus getFileStatus(FileHeader header) throws DbException;

	/**
	 * Returns how much of the given file has been received.
	 */
	FileStatus getFileStatus(Transaction txn, FileHeader header)
			throws DbException;

	/**
	 * Returns a stream for reading the given file, which must have been
	 * fully received. The stream reads the file's chunks from the database
	 * one at a time, so the whole file is never held in memory.
	 *
	 * @throws org.briarproject.bramble.api.db.NoSuchMessageException If the
	 * file has not been fully received
	 */
	InputStream getFile(FileHeader header) throws DbException;

	/**
	 * Returns a stream for reading the given file, which must have been
	 * fully received. The stream may be used after the given transaction
	 * has ended.
	 *
	 * @throws org.briarproject.bramble.api.db.NoSuchMessageException If the
	 * file has not been fully received
	 */
	InputStream getFile(Transaction txn, FileHeader header)
			throws DbException;

	/**
	 * Returns the bytes of the given chunk of a file, which must have been
	 * fully received, for random access when playing audio or video.
	 *
	 * @throws org.briarproject.bramble.api.db.NoSuchMessageException If the
	 * file has not been fully received or the index is out of range
	 */
	byte[] getFileChunk(FileHeader header, int index) throws DbException;

	/**
	 * Returns the header of the file whose manifest has the given message ID.
	 *
	 * @throws org.briarproject.bramble.api.db.NoSuchMessageException If the
	 * message is not a file manifest in the given conversation, or has not
	 * arrived
	 */
	FileHeader getFileHeader(Transaction txn, GroupId groupId,
			MessageId manifestId) throws DbException;

	/**
	 * Returns the header of the file whose manifest has the given message ID.
	 *
	 * @throws org.briarproject.bramble.api.db.NoSuchMessageException If the
	 * message is not a file manifest in the given conversation, or has not
	 * arrived
	 */
	FileHeader getFileHeader(GroupId groupId, MessageId manifestId)
			throws DbException;

	/**
	 * Returns the ID of the contact with the given private conversation.
	 */
	ContactId getContactId(GroupId g) throws DbException;

	/**
	 * Returns the ID of the private conversation with the given contact.
	 */
	GroupId getConversationId(ContactId c) throws DbException;

	/**
	 * Returns the ID of the private conversation with the given contact.
	 */
	GroupId getConversationId(Transaction txn, ContactId c) throws DbException;

	/**
	 * Returns the text of the private message with the given ID, or null if
	 * the private message has no text.
	 */
	@Nullable
	String getMessageText(MessageId m) throws DbException;

	/**
	 * Returns the text of the private message with the given ID, or null if
	 * the private message has no text.
	 */
	@Nullable
	String getMessageText(Transaction txn, MessageId m) throws DbException;

	/**
	 * Returns the private message format supported by the given contact.
	 */
	PrivateMessageFormat getContactMessageFormat(Transaction txn, ContactId c)
			throws DbException;
}
