package org.briarproject.briar.api.privategroup;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface PrivateGroupFactory {

	/**
	 * Creates a private group with the given name and author.
	 */
	PrivateGroup createPrivateGroup(String name, Author creator);

	/**
	 * Creates a private group with the given name and author. If
	 * {@code creatorOnly} is true, only the creator can post in it.
	 */
	PrivateGroup createPrivateGroup(String name, Author creator,
			boolean creatorOnly);

	/**
	 * Creates a private group with the given name, author and salt.
	 */
	PrivateGroup createPrivateGroup(String name, Author creator, byte[] salt);

	/**
	 * Creates a private group with the given name, author and salt. If
	 * {@code creatorOnly} is true, only the creator can post in it.
	 * <p>
	 * Such a group has a different
	 * {@link org.briarproject.bramble.api.sync.GroupId} from a regular group
	 * with the same name, creator and salt, because the flag is part of the
	 * group descriptor.
	 */
	PrivateGroup createPrivateGroup(String name, Author creator, byte[] salt,
			boolean creatorOnly);

	/**
	 * Parses a group and returns the corresponding PrivateGroup.
	 */
	PrivateGroup parsePrivateGroup(Group group) throws FormatException;

}
