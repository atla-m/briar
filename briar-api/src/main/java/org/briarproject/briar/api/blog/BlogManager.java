package org.briarproject.briar.api.blog;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
public interface BlogManager {

	/**
	 * The unique ID of the blog client.
	 */
	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.blog");

	/**
	 * The current major version of the blog client.
	 */
	int MAJOR_VERSION = 0;

	/**
	 * The current minor version of the blog client.
	 */
	int MINOR_VERSION = 2;

	/**
	 * Adds the given {@link Blog}.
	 */
	void addBlog(Blog b) throws DbException;

	/**
	 * Adds the given {@link Blog} within the given {@link Transaction}.
	 */
	void addBlog(Transaction txn, Blog b) throws DbException;

	/**
	 * Returns true if a blog can be removed.
	 */
	boolean canBeRemoved(Blog b) throws DbException;

	/**
	 * Removes and deletes a blog.
	 */
	void removeBlog(Blog b) throws DbException;

	/**
	 * Removes and deletes a blog with the given {@link Transaction}.
	 */
	void removeBlog(Transaction txn, Blog b) throws DbException;

	/**
	 * Returns a timestamp for a new message in the given blog. For a
	 * channel this is no earlier than the given timestamp and later than
	 * every message we have already added to the channel; for any other
	 * blog the given timestamp is returned unchanged.
	 * <p/>
	 * A channel is published as a file whose messages are ordered by
	 * timestamp, and a reader can ask a mirror for only the part of that
	 * file it does not have yet. That only works if publishing again
	 * leaves the beginning of the file alone, which in turn needs each
	 * new message to sort after the ones already there. Two messages
	 * written in the same millisecond would otherwise be ordered by
	 * their IDs, which are hashes and say nothing about time. A personal
	 * blog syncs message by message, and an RSS post keeps the date its
	 * feed gave it, so neither is touched.
	 */
	long getNextTimestamp(GroupId g, long earliest) throws DbException;

	/**
	 * Returns a timestamp for a new message in the given blog, with the
	 * given {@link Transaction}.
	 */
	long getNextTimestamp(Transaction txn, GroupId g, long earliest)
			throws DbException;

	/**
	 * Stores an image to be carried by a blog post, which must then
	 * reference it by the returned header. The image is not sent until a
	 * post does.
	 */
	AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream in)
			throws DbException, IOException;

	/**
	 * Stores a file to be carried by a blog post, splitting it into chunks
	 * so it can arrive piece by piece. The file is not sent until a post
	 * references it.
	 */
	FileHeader addLocalFile(GroupId groupId, long timestamp, String name,
			String contentType, StreamSource source)
			throws DbException, IOException;

	/**
	 * Returns the header of the file whose manifest has the given ID.
	 */
	FileHeader getFileHeader(GroupId groupId, MessageId manifestId)
			throws DbException;

	/**
	 * Returns how much of the given file has arrived.
	 */
	FileStatus getFileStatus(FileHeader header) throws DbException;

	/**
	 * Returns true if the given message body is the chunk at the given
	 * index of the file the given manifest describes, so that a chunk from
	 * a mirror can be checked before it is stored.
	 */
	boolean isChunkOf(Transaction txn, MessageId manifestId, int index,
			byte[] body, int descriptorLength) throws DbException;

	/**
	 * Asks for the chunks of a file its author held back because it is
	 * larger than
	 * {@link org.briarproject.briar.api.attachment.MediaConstants#MAX_PUSHED_FILE_SIZE}.
	 */
	void requestFile(FileHeader header) throws DbException;

	/**
	 * Returns a stream for reading a file that has fully arrived.
	 */
	InputStream getFile(FileHeader header) throws DbException;

	/**
	 * Returns the bytes of one chunk of a file that has fully arrived, for
	 * playing audio and video without writing the file to disk.
	 */
	byte[] getFileChunk(FileHeader header, int index) throws DbException;

	/**
	 * Removes an image that no post has referenced yet.
	 */
	void removeAttachment(AttachmentHeader header) throws DbException;

	/**
	 * Removes a file that no post has referenced yet.
	 */
	void removeFile(FileHeader header) throws DbException;

	/**
	 * Stores a local blog post.
	 */
	void addLocalPost(BlogPost p) throws DbException;

	/**
	 * Stores a local blog post.
	 */
	void addLocalPost(Transaction txn, BlogPost p) throws DbException;

	/**
	 * Adds a comment to an existing blog post or reblogs it.
	 */
	void addLocalComment(LocalAuthor author, GroupId groupId,
			@Nullable String comment, BlogPostHeader parentHeader)
			throws DbException;

	/**
	 * Adds a comment to an existing blog post or reblogs it.
	 */
	void addLocalComment(Transaction txn, LocalAuthor author,
			GroupId groupId, @Nullable String comment,
			BlogPostHeader parentHeader) throws DbException;

	/**
	 * Returns the blog with the given ID.
	 */
	Blog getBlog(GroupId g) throws DbException;

	/**
	 * Returns the blog with the given ID.
	 */
	Blog getBlog(Transaction txn, GroupId g) throws DbException;

	/**
	 * Returns all blogs owned by the given localAuthor.
	 */
	Collection<Blog> getBlogs(LocalAuthor localAuthor) throws DbException;

	/**
	 * Returns only the personal blog of the given author.
	 */
	Blog getPersonalBlog(Author author);

	/**
	 * Returns all blogs to which the user subscribes.
	 */
	Collection<Blog> getBlogs() throws DbException;

	/**
	 * Returns all blogs to which the user subscribes.
	 */
	Collection<Blog> getBlogs(Transaction txn) throws DbException;

	/**
	 * Returns the group IDs of all blogs to which the user subscribes.
	 */
	Collection<GroupId> getBlogIds(Transaction txn) throws DbException;

	/**
	 * Returns the header of the blog post with the given ID.
	 */
	BlogPostHeader getPostHeader(Transaction txn, GroupId g, MessageId m)
			throws DbException;

	/**
	 * Returns the text of the blog post with the given ID.
	 */
	String getPostText(MessageId m) throws DbException;

	/**
	 * Returns the text of the blog post with the given ID.
	 */
	String getPostText(Transaction txn, MessageId m) throws DbException;

	/**
	 * Returns the headers of all posts in the given blog.
	 */
	Collection<BlogPostHeader> getPostHeaders(GroupId g) throws DbException;

	/**
	 * Returns the headers of all posts in the given blog.
	 */
	List<BlogPostHeader> getPostHeaders(Transaction txn, GroupId g)
			throws DbException;

	/**
	 * Marks a blog post as read or unread.
	 */
	void setReadFlag(MessageId m, boolean read) throws DbException;

	/**
	 * Marks a blog post as read or unread.
	 */
	void setReadFlag(Transaction txn, MessageId m, boolean read) throws DbException;

	/**
	 * Registers a hook to be called whenever a blog is removed.
	 */
	void registerRemoveBlogHook(RemoveBlogHook hook);

	interface RemoveBlogHook {
		/**
		 * Called when a blog is being removed.
		 *
		 * @param txn A read-write transaction
		 * @param b The blog that is being removed
		 */
		void removingBlog(Transaction txn, Blog b) throws DbException;
	}

}
