package org.briarproject.briar.blog;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.briar.api.blog.BlogPost;
import org.briarproject.briar.api.blog.BlogPostFactory;
import org.briarproject.briar.api.blog.MessageType;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_COMMENT_TEXT_LENGTH;
import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_POST_ATTACHMENTS;
import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_POST_TEXT_LENGTH;
import static org.briarproject.briar.blog.BlogPostValidator.getSignedPost;
import static org.briarproject.briar.api.blog.MessageType.COMMENT;
import static org.briarproject.briar.api.blog.MessageType.POST;
import static org.briarproject.briar.api.blog.MessageType.WRAPPED_COMMENT;
import static org.briarproject.briar.api.blog.MessageType.WRAPPED_POST;

@Immutable
@NotNullByDefault
class BlogPostFactoryImpl implements BlogPostFactory {

	private final ClientHelper clientHelper;
	private final Clock clock;

	@Inject
	BlogPostFactoryImpl(ClientHelper clientHelper, Clock clock) {
		this.clientHelper = clientHelper;
		this.clock = clock;
	}

	@Override
	public BlogPost createBlogPost(GroupId groupId, long timestamp,
			@Nullable MessageId parent, LocalAuthor author, String text)
			throws FormatException, GeneralSecurityException {

		// Validate the arguments
		int textLength = StringUtils.toUtf8(text).length;
		if (textLength > MAX_BLOG_POST_TEXT_LENGTH)
			throw new IllegalArgumentException();

		// Serialise the data to be signed
		BdfList signed = getSignedPost(groupId, timestamp, text, null);

		// Generate the signature
		byte[] sig = clientHelper
				.sign(SIGNING_LABEL_POST, signed, author.getPrivateKey());

		// Serialise the signed message
		BdfList message = BdfList.of(POST.getInt(), text, sig);
		Message m = clientHelper.createMessage(groupId, timestamp, message);
		return new BlogPost(m, parent, author);
	}

	@Override
	public BlogPost createBlogPost(GroupId groupId, long timestamp,
			@Nullable MessageId parent, LocalAuthor author,
			@Nullable String text, List<AttachmentHeader> attachments,
			List<FileHeader> files)
			throws FormatException, GeneralSecurityException {

		if (attachments.isEmpty() && files.isEmpty())
			throw new IllegalArgumentException();
		// The validator requires text, if present, to have length, so an
		// empty string would sign a post no reader accepts
		if (text != null && text.isEmpty()) text = null;
		if (attachments.size() + files.size() > MAX_BLOG_POST_ATTACHMENTS)
			throw new IllegalArgumentException();
		if (text != null && StringUtils.toUtf8(text).length >
				MAX_BLOG_POST_TEXT_LENGTH) {
			throw new IllegalArgumentException();
		}

		// An image is named by the message holding it, a file by the
		// message holding its manifest, with its name and size so they
		// can be shown before it has arrived
		BdfList headers = new BdfList();
		for (AttachmentHeader a : attachments) {
			headers.add(BdfList.of(a.getMessageId(), a.getContentType()));
		}
		for (FileHeader f : files) {
			headers.add(BdfList.of(f.getManifestId(), f.getContentType(),
					f.getName(), f.getSize()));
		}

		BdfList signed = getSignedPost(groupId, timestamp, text, headers);
		byte[] sig = clientHelper
				.sign(SIGNING_LABEL_POST, signed, author.getPrivateKey());
		BdfList message = BdfList.of(POST.getInt(), text, headers, sig);
		Message m = clientHelper.createMessage(groupId, timestamp, message);
		return new BlogPost(m, parent, author, attachments, files,
				text != null);
	}

	@Override
	public Message createBlogComment(GroupId groupId, LocalAuthor author,
			@Nullable String comment, MessageId parentOriginalId,
			MessageId parentCurrentId)
			throws FormatException, GeneralSecurityException {

		if (comment != null) {
			int commentLength = StringUtils.toUtf8(comment).length;
			if (commentLength == 0) throw new IllegalArgumentException();
			if (commentLength > MAX_BLOG_COMMENT_TEXT_LENGTH)
				throw new IllegalArgumentException();
		}

		long timestamp = clock.currentTimeMillis();

		// Generate the signature
		BdfList signed = BdfList.of(groupId, timestamp, comment,
				parentOriginalId, parentCurrentId);
		byte[] sig = clientHelper
				.sign(SIGNING_LABEL_COMMENT, signed, author.getPrivateKey());

		// Serialise the signed message
		BdfList message = BdfList.of(COMMENT.getInt(), comment,
				parentOriginalId, parentCurrentId, sig);
		return clientHelper.createMessage(groupId, timestamp, message);
	}

	@Override
	public Message wrapPost(GroupId groupId, byte[] descriptor,
			long timestamp, BdfList body) throws FormatException {

		if (getType(body) != POST)
			throw new IllegalArgumentException("Needs to wrap a POST");

		// Serialise the message. A post with attachments carries their
		// headers between the text and the signature, and the signature
		// covers them, so the copy has to carry them too or nobody could
		// verify it
		BdfList message;
		if (body.size() == 4) {
			String text = body.getOptionalString(1);
			BdfList headers = body.getList(2);
			byte[] signature = body.getRaw(3);
			message = BdfList.of(WRAPPED_POST.getInt(), descriptor,
					timestamp, text, headers, signature);
		} else {
			String text = body.getString(1);
			byte[] signature = body.getRaw(2);
			message = BdfList.of(WRAPPED_POST.getInt(), descriptor,
					timestamp, text, signature);
		}
		return clientHelper
				.createMessage(groupId, clock.currentTimeMillis(), message);
	}

	@Override
	public Message rewrapWrappedPost(GroupId groupId, BdfList body)
			throws FormatException {

		if (getType(body) != WRAPPED_POST)
			throw new IllegalArgumentException("Needs to wrap a WRAPPED_POST");

		// Serialise the message, keeping the attachment headers if the
		// original post carried any
		byte[] descriptor = body.getRaw(1);
		long timestamp = body.getLong(2);
		BdfList message;
		if (body.size() == 6) {
			String text = body.getOptionalString(3);
			BdfList headers = body.getList(4);
			byte[] signature = body.getRaw(5);
			message = BdfList.of(WRAPPED_POST.getInt(), descriptor,
					timestamp, text, headers, signature);
		} else {
			String text = body.getString(3);
			byte[] signature = body.getRaw(4);
			message = BdfList.of(WRAPPED_POST.getInt(), descriptor,
					timestamp, text, signature);
		}
		return clientHelper
				.createMessage(groupId, clock.currentTimeMillis(), message);
	}

	@Override
	public Message wrapComment(GroupId groupId, byte[] descriptor,
			long timestamp, BdfList body, MessageId parentCurrentId)
			throws FormatException {

		if (getType(body) != COMMENT)
			throw new IllegalArgumentException("Needs to wrap a COMMENT");

		// Serialise the message
		String comment = body.getOptionalString(1);
		byte[] pOriginalId = body.getRaw(2);
		byte[] oldParentId = body.getRaw(3);
		byte[] signature = body.getRaw(4);
		BdfList message = BdfList.of(WRAPPED_COMMENT.getInt(), descriptor,
				timestamp, comment, pOriginalId, oldParentId, signature,
				parentCurrentId);
		return clientHelper
				.createMessage(groupId, clock.currentTimeMillis(), message);
	}

	@Override
	public Message rewrapWrappedComment(GroupId groupId, BdfList body,
			MessageId parentCurrentId) throws FormatException {

		if (getType(body) != WRAPPED_COMMENT)
			throw new IllegalArgumentException(
					"Needs to wrap a WRAPPED_COMMENT");

		// Serialise the message
		byte[] descriptor = body.getRaw(1);
		long timestamp = body.getLong(2);
		String comment = body.getOptionalString(3);
		byte[] pOriginalId = body.getRaw(4);
		byte[] oldParentId = body.getRaw(5);
		byte[] signature = body.getRaw(6);
		BdfList message = BdfList.of(WRAPPED_COMMENT.getInt(), descriptor,
				timestamp, comment, pOriginalId, oldParentId, signature,
				parentCurrentId);
		return clientHelper
				.createMessage(groupId, clock.currentTimeMillis(), message);
	}

	private MessageType getType(BdfList body) throws FormatException {
		return MessageType.valueOf(body.getInt(0));
	}
}
