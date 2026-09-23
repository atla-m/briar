package org.briarproject.briar.api.privategroup;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.client.MessageTracker.GroupCount;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

@NotNullByDefault
public interface PrivateGroupManager {

	/**
	 * The unique ID of the private group client.
	 */
	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.privategroup");

	/**
	 * The current major version of the private group client.
	 */
	int MAJOR_VERSION = 0;

	/**
	 * The current minor version of the private group client.
	 * <p>
	 * Version 0.1 added image attachments to posts. Version 0.2 added
	 * chunked file sharing.
	 */
	int MINOR_VERSION = 2;

	/**
	 * Adds a new private group and joins it.
	 *
	 * @param group The private group to add
	 * @param joinMsg The new member's join message
	 * @param creator True if the group is added by its creator
	 */
	void addPrivateGroup(PrivateGroup group, GroupMessage joinMsg,
			boolean creator) throws DbException;

	/**
	 * Adds a new private group and joins it.
	 *
	 * @param group The private group to add
	 * @param joinMsg The new member's join message
	 * @param creator True if the group is added by its creator
	 */
	void addPrivateGroup(Transaction txn, PrivateGroup group,
			GroupMessage joinMsg, boolean creator) throws DbException;

	/**
	 * Removes a dissolved private group.
	 */
	void removePrivateGroup(Transaction txn, GroupId g) throws DbException;

	/**
	 * Removes a dissolved private group.
	 */
	void removePrivateGroup(GroupId g) throws DbException;

	/**
	 * Returns the ID of the user's previous message sent to the group
	 */
	MessageId getPreviousMsgId(Transaction txn, GroupId g) throws DbException;

	/**
	 * Returns the ID of the user's previous message sent to the group
	 */
	MessageId getPreviousMsgId(GroupId g) throws DbException;

	/**
	 * Marks the given private group as dissolved.
	 */
	void markGroupDissolved(Transaction txn, GroupId g) throws DbException;

	/**
	 * Returns true if the given private group has been dissolved.
	 */
	boolean isDissolved(Transaction txn, GroupId g) throws DbException;

	/**
	 * Returns true if the given private group has been dissolved.
	 */
	boolean isDissolved(GroupId g) throws DbException;

	/**
	 * Stores and sends a local private group message.
	 */
	GroupMessageHeader addLocalMessage(GroupMessage p) throws DbException;

	/**
	 * Stores and sends a local private group message.
	 */
	GroupMessageHeader addLocalMessage(Transaction txn, GroupMessage p)
			throws DbException;

	/**
	 * Stores a local attachment for the given private group. The attachment
	 * is not shared with other members until a post that references it is
	 * added with {@link #addLocalMessage(GroupMessage)}.
	 *
	 * @throws FileTooBigException If the attachment is too big
	 */
	AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream in)
			throws DbException, IOException;

	/**
	 * Removes an unsent attachment.
	 */
	void removeAttachment(AttachmentHeader header) throws DbException;

	/**
	 * Stores a local file for the given private group, split into chunks
	 * that each fit into a single message, plus a manifest listing the
	 * chunks. The file is not shared with other members until a post that
	 * references it is added with {@link #addLocalMessage(GroupMessage)}.
	 *
	 * @throws FileTooBigException If the file is larger than
	 * {@link PrivateGroupConstants#MAX_FILE_SIZE}
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
	 * Asks for the chunks of a file its sender held back because it is
	 * larger than
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#MAX_PUSHED_FILE_SIZE}.
	 * Once whoever holds it receives the request, the file goes to every
	 * member it reaches, not only to us.
	 */
	void requestFile(FileHeader header) throws DbException;

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
	 * fully received. The stream reads the file's chunks from the database
	 * one at a time, in transactions of its own, so it may be used after
	 * the given transaction has ended.
	 *
	 * @throws org.briarproject.bramble.api.db.NoSuchMessageException If the
	 * file has not been fully received
	 */
	InputStream getFile(Transaction txn, FileHeader header)
			throws DbException;

	/**
	 * Returns the bytes of the given chunk of a file, which must have been
	 * fully received. Every chunk except the last holds
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#FILE_CHUNK_PAYLOAD_LENGTH}
	 * bytes, so byte offset {@code n} of the file is in chunk
	 * {@code n / payloadLength}. This gives random access for playing audio
	 * and video straight from the database, without writing the decrypted
	 * file to disk.
	 *
	 * @throws org.briarproject.bramble.api.db.NoSuchMessageException If the
	 * file has not been fully received or the index is out of range
	 */
	byte[] getFileChunk(FileHeader header, int index) throws DbException;

	/**
	 * Returns the header of the file whose manifest has the given message ID.
	 * This allows a file to be looked up when only its manifest ID is known,
	 * for example when a post references a chunked image the same way it
	 * references a single-message attachment.
	 *
	 * @throws org.briarproject.bramble.api.db.NoSuchMessageException If the
	 * message is not a file manifest in the given group, or has not arrived
	 */
	FileHeader getFileHeader(Transaction txn, GroupId groupId,
			MessageId manifestId) throws DbException;

	/**
	 * Returns the header of the file whose manifest has the given message ID.
	 *
	 * @throws org.briarproject.bramble.api.db.NoSuchMessageException If the
	 * message is not a file manifest in the given group, or has not arrived
	 */
	FileHeader getFileHeader(GroupId groupId, MessageId manifestId)
			throws DbException;

	/**
	 * Returns the private group with the given ID.
	 */
	PrivateGroup getPrivateGroup(GroupId g) throws DbException;

	/**
	 * Returns the private group with the given ID.
	 */
	PrivateGroup getPrivateGroup(Transaction txn, GroupId g) throws DbException;

	/**
	 * Returns all private groups the user is a member of.
	 */
	Collection<PrivateGroup> getPrivateGroups() throws DbException;

	/**
	 * Returns all private groups the user is a member of.
	 */
	Collection<PrivateGroup> getPrivateGroups(Transaction txn)
			throws DbException;

	/**
	 * Returns true if the given private group was created by us.
	 */
	boolean isOurPrivateGroup(Transaction txn, PrivateGroup g)
			throws DbException;

	/**
	 * Returns the text of the private group message with the given ID.
	 */
	String getMessageText(MessageId m) throws DbException;

	/**
	 * Returns the text of the private group message with the given ID.
	 */
	String getMessageText(Transaction txn, MessageId m) throws DbException;

	/**
	 * Returns the headers of all messages in the given private group.
	 */
	Collection<GroupMessageHeader> getHeaders(GroupId g) throws DbException;

	/**
	 * Returns the headers of all messages in the given private group.
	 */
	List<GroupMessageHeader> getHeaders(Transaction txn, GroupId g)
			throws DbException;

	/**
	 * Returns all members of the given private group.
	 */
	Collection<GroupMember> getMembers(GroupId g) throws DbException;

	/**
	 * Returns all members of the given private group.
	 */
	Collection<GroupMember> getMembers(Transaction txn, GroupId g)
			throws DbException;

	/**
	 * Returns true if the given author is a member of the given private group.
	 */
	boolean isMember(Transaction txn, GroupId g, Author a) throws DbException;

	/**
	 * Returns the group count for the given private group.
	 */
	GroupCount getGroupCount(Transaction txn, GroupId g) throws DbException;

	/**
	 * Returns the group count for the given private group.
	 */
	GroupCount getGroupCount(GroupId g) throws DbException;

	/**
	 * Marks a message as read or unread and updates the group count.
	 */
	void setReadFlag(Transaction txn, GroupId g, MessageId m, boolean read)
			throws DbException;

	/**
	 * Marks a message as read or unread and updates the group count.
	 */
	void setReadFlag(GroupId g, MessageId m, boolean read) throws DbException;

	/**
	 * Called when a contact relationship has been revealed between the user
	 * and the given author in the given private group.
	 *
	 * @param byContact True if the contact revealed the relationship first,
	 * otherwise false.
	 */
	void relationshipRevealed(Transaction txn, GroupId g, AuthorId a,
			boolean byContact) throws FormatException, DbException;

	/**
	 * Registers a hook to be called when members are added or private groups
	 * are removed.
	 */
	void registerPrivateGroupHook(PrivateGroupHook hook);

	@NotNullByDefault
	interface PrivateGroupHook {

		/**
		 * Called when a member is being added to a private group.
		 *
		 * @param txn A read-write transaction
		 * @param g The ID of the private group
		 * @param a The member that is being added
		 */
		void addingMember(Transaction txn, GroupId g, Author a)
				throws DbException;

		/**
		 * Called when a private group is being removed.
		 *
		 * @param txn A read-write transaction
		 * @param g The ID of the private group that is being removed
		 */
		void removingGroup(Transaction txn, GroupId g) throws DbException;

	}

}
