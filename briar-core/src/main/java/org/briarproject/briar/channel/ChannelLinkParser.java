package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorFactory;
import org.briarproject.bramble.util.Base32;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import okhttp3.HttpUrl;

import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.util.StringUtils.truncateUtf8;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_FORMAT_VERSION;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_REGEX;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_LINK_BYTES;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_MIRRORS;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_MIRROR_LENGTH;
import static org.briarproject.briar.api.channel.ChannelTitle.isAllowed;

/**
 * Reads a channel link: the channel's title, public key and mirrors.
 * Shared by the channel manager, which subscribes from links, and the
 * private message validator, which checks a forwarded post against the
 * key in the link it came with.
 */
@Immutable
@NotNullByDefault
public class ChannelLinkParser {

	private final ClientHelper clientHelper;
	private final CryptoComponent crypto;
	private final AuthorFactory authorFactory;
	private final BlogFactory blogFactory;

	@Inject
	ChannelLinkParser(ClientHelper clientHelper, CryptoComponent crypto,
			AuthorFactory authorFactory, BlogFactory blogFactory) {
		this.clientHelper = clientHelper;
		this.crypto = crypto;
		this.authorFactory = authorFactory;
		this.blogFactory = blogFactory;
	}

	/**
	 * Returns the channel a link names and the mirrors it carries.
	 *
	 * @throws FormatException If the link is malformed
	 */
	public ParsedLink parse(String link) throws FormatException {
		Matcher matcher = LINK_REGEX.matcher(link);
		if (!matcher.find()) throw new FormatException();
		// Discard the prefix and anything around the link
		byte[] raw = Base32.decode(matcher.group(2), false);
		if (raw.length > MAX_LINK_BYTES) throw new FormatException();
		BdfList parsed = clientHelper.toList(raw);
		checkSize(parsed, 4);
		if (parsed.getInt(0) != LINK_FORMAT_VERSION)
			throw new FormatException();
		String title = parsed.getString(1);
		// A title that could disguise itself, or the text after it, is
		// refused rather than cleaned, since the title is part of the
		// channel's identity
		if (!isAllowed(title)) throw new FormatException();
		checkLength(title, 1, MAX_AUTHOR_NAME_LENGTH);
		PublicKey publicKey;
		try {
			publicKey = crypto.getSignatureKeyParser()
					.parsePublicKey(parsed.getRaw(2));
		} catch (GeneralSecurityException e) {
			// A link comes from outside, so a key of the wrong length, or
			// one that isn't a point on the curve, is a malformed link
			throw new FormatException();
		}
		List<String> mirrors = parseMirrors(parsed.getList(3));
		// A link comes from outside, and a mirror the HTTP client can't
		// parse would throw at every scheduled fetch
		for (String mirror : mirrors) {
			if (HttpUrl.parse(mirror) == null) throw new FormatException();
		}
		String name = truncateUtf8(title, MAX_AUTHOR_NAME_LENGTH);
		Author author = authorFactory.createAuthor(name, publicKey);
		return new ParsedLink(blogFactory.createChannelBlog(author), mirrors);
	}

	public static List<String> parseMirrors(BdfList list)
			throws FormatException {
		if (list.size() > MAX_MIRRORS) throw new FormatException();
		List<String> mirrors = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			String mirror = list.getString(i);
			checkLength(mirror, 1, MAX_MIRROR_LENGTH);
			mirrors.add(mirror);
		}
		return mirrors;
	}

	public static class ParsedLink {

		public final Blog blog;
		public final List<String> mirrors;

		public ParsedLink(Blog blog, List<String> mirrors) {
			this.blog = blog;
			this.mirrors = mirrors;
		}
	}
}
