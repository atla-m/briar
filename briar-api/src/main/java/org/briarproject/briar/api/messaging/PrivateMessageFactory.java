package org.briarproject.briar.api.messaging;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
public interface PrivateMessageFactory {

	/**
	 * Creates a private message in the
	 * {@link PrivateMessageFormat#TEXT_ONLY TEXT_ONLY} format.
	 */
	PrivateMessage createLegacyPrivateMessage(GroupId groupId, long timestamp,
			String text) throws FormatException;

	/**
	 * Creates a private message in the
	 * {@link PrivateMessageFormat#TEXT_IMAGES TEXT_IMAGES} format. This format
	 * requires the contact to support client version 0.1 or higher.
	 */
	PrivateMessage createPrivateMessage(GroupId groupId, long timestamp,
			@Nullable String text, List<AttachmentHeader> headers)
			throws FormatException;

	/**
	 * Creates a private message in the
	 * {@link PrivateMessageFormat#TEXT_IMAGES_AUTO_DELETE TEXT_IMAGES_AUTO_DELETE}
	 * format. This format requires the contact to support client version 0.3
	 * or higher.
	 */
	PrivateMessage createPrivateMessage(GroupId groupId, long timestamp,
			@Nullable String text, List<AttachmentHeader> headers,
			long autoDeleteTimer) throws FormatException;

	/**
	 * Creates a private message in the
	 * {@link PrivateMessageFormat#TEXT_IMAGES_AUTO_DELETE_FILES
	 * TEXT_IMAGES_AUTO_DELETE_FILES} format, with optional text, image
	 * attachments and shared files. This format requires the contact to
	 * support client version 0.4 or higher.
	 */
	PrivateMessage createPrivateMessage(GroupId groupId, long timestamp,
			@Nullable String text, List<AttachmentHeader> headers,
			List<FileHeader> fileHeaders, long autoDeleteTimer)
			throws FormatException;

	/**
	 * Creates a private message in the
	 * {@link PrivateMessageFormat#TEXT_IMAGES_AUTO_DELETE_FILES_FORWARD
	 * TEXT_IMAGES_AUTO_DELETE_FILES_FORWARD} format, which can carry the
	 * link of the channel a forwarded post came from. This format requires
	 * the contact to support client version 0.5 or higher.
	 *
	 * @param channelLink the channel the text was forwarded from, or null
	 * for an ordinary message
	 */
	PrivateMessage createPrivateMessage(GroupId groupId, long timestamp,
			@Nullable String text, List<AttachmentHeader> headers,
			List<FileHeader> fileHeaders, long autoDeleteTimer,
			@Nullable String channelLink) throws FormatException;
}
