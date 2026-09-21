package org.briarproject.briar.api.privategroup;

import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.briar.api.client.NamedGroup;
import org.briarproject.briar.api.sharing.Shareable;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PrivateGroup extends NamedGroup implements Shareable {

	private final Author creator;
	private final boolean creatorOnly;

	public PrivateGroup(Group group, String name, Author creator, byte[] salt) {
		this(group, name, creator, salt, false);
	}

	public PrivateGroup(Group group, String name, Author creator, byte[] salt,
			boolean creatorOnly) {
		super(group, name, salt);
		this.creator = creator;
		this.creatorOnly = creatorOnly;
	}

	public Author getCreator() {
		return creator;
	}

	/**
	 * Returns true if only the creator can post in this group; all other
	 * members can only read. This is enforced by the message validator on
	 * every member's device, so a post from anyone other than the creator
	 * is rejected as invalid.
	 */
	public boolean isCreatorOnly() {
		return creatorOnly;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof PrivateGroup && super.equals(o);
	}

}
