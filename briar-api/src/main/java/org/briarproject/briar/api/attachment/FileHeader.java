package org.briarproject.briar.api.attachment;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.channel.ChannelTitle;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
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

	/**
	 * The name shown for a file whose own name is nothing but characters
	 * we don't show.
	 */
	public static final String NAMELESS = "file";

	private final GroupId groupId;
	private final MessageId manifestId;
	private final String name, contentType;
	private final long size;

	public FileHeader(GroupId groupId, MessageId manifestId, String name,
			String contentType, long size) {
		this.groupId = groupId;
		this.manifestId = manifestId;
		this.name = cleanName(name);
		this.contentType = contentType;
		this.size = size;
	}

	/**
	 * Removes from a file name the characters that could make it look like
	 * something else: line breaks, control characters and invisible
	 * characters, the same set a channel's title may not hold. A name
	 * using them can be shown with its real type hidden, as
	 * "report‮fdp.apk" shows as "reportkpa.pdf" and
	 * "video.mp4 .apk" shows as "video.mp4" on one line with the
	 * ".apk" out of sight. The name comes from whoever sent the file, so
	 * it is cleaned where it is read, not only where it is written.
	 */
	public static String cleanName(String name) {
		String clean = ChannelTitle.clean(name);
		return clean.isEmpty() ? NAMELESS : clean;
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
	 * Returns the name to save the file under: the file's name with its
	 * extension replaced by the one that belongs to its content type, so a
	 * file saved as a video is one. The sender chooses both the name and
	 * the content type, but the content type is what decides how the file
	 * is opened here, so it is the one the saved name must agree with.
	 *
	 * @param extension The extension for the file's content type, without
	 * the dot, or null if the type has no known extension, in which case
	 * the name's own extension is dropped rather than trusted.
	 */
	public String getSaveName(@Nullable String extension) {
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		if (extension == null) return base;
		String suffix = "." + extension;
		if (endsWithIgnoreCase(name, suffix)) return name;
		// "video.mp4.apk" saved as a video is "video.mp4", not
		// "video.mp4.mp4"
		if (endsWithIgnoreCase(base, suffix)) return base;
		return base + suffix;
	}

	private static boolean endsWithIgnoreCase(String s, String suffix) {
		return s.regionMatches(true, s.length() - suffix.length(), suffix, 0,
				suffix.length());
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
