package org.briarproject.briar.api.attachment;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.regex.Pattern;

import javax.annotation.concurrent.Immutable;

/**
 * Describes a file shared in a private group or a private conversation. The
 * header is carried by the message that shares the file, so the recipients
 * learn the file's name and size before any of its bytes arrive. The
 * manifest ID identifies the manifest message that lists the file's chunks.
 */
@Immutable
@NotNullByDefault
public class FileHeader {

	// Embeddings, overrides and isolates, U+202A to U+202E and U+2066 to
	// U+2069. The marks U+200E and U+200F only affect neutral characters
	// beside them, so they are left alone.
	private static final Pattern DIRECTION_OVERRIDES =
			Pattern.compile("[\u202A-\u202E\u2066-\u2069]");

	private final GroupId groupId;
	private final MessageId manifestId;
	private final String name, contentType;
	private final long size;

	public FileHeader(GroupId groupId, MessageId manifestId, String name,
			String contentType, long size) {
		this.groupId = groupId;
		this.manifestId = manifestId;
		this.name = withoutDirectionOverrides(name);
		this.contentType = contentType;
		this.size = size;
	}

	/**
	 * Removes the characters that override the direction of the text
	 * around them. A file name using them can be shown with its real type
	 * hidden: "report\u202Efdp.apk" shows as "reportkpa.pdf". Names don't
	 * need them, in Persian or any other language.
	 */
	public static String withoutDirectionOverrides(String s) {
		return DIRECTION_OVERRIDES.matcher(s).replaceAll("");
	}

	/**
	 * Returns true if the string holds a character that overrides the
	 * direction of the text around it.
	 */
	public static boolean hasDirectionOverride(String s) {
		return DIRECTION_OVERRIDES.matcher(s).find();
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public MessageId getManifestId() {
		return manifestId;
	}

	public String getName() {
		return name;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSize() {
		return size;
	}

	/**
	 * Returns the number of chunks the file is split into.
	 */
	public int getChunkCount() {
		int payload = MediaConstants.FILE_CHUNK_PAYLOAD_LENGTH;
		return (int) ((size + payload - 1) / payload);
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof FileHeader) {
			FileHeader h = (FileHeader) o;
			return groupId.equals(h.groupId) && manifestId.equals(h.manifestId);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return manifestId.hashCode();
	}
}
