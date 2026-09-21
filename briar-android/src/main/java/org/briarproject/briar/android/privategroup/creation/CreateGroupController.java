package org.briarproject.briar.android.privategroup.creation;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.android.contactselection.ContactSelectorController;
import org.briarproject.briar.android.contactselection.SelectableContactItem;
import org.briarproject.briar.android.controller.handler.ResultExceptionHandler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import androidx.annotation.Nullable;

@NotNullByDefault
public interface CreateGroupController
		extends ContactSelectorController<SelectableContactItem> {

	/**
	 * Creates a private group. If {@code creatorOnly} is true, only the
	 * creator can post in the group.
	 */
	void createGroup(String name, boolean creatorOnly,
			ResultExceptionHandler<GroupId, DbException> result);

	void sendInvitation(GroupId g, Collection<ContactId> contacts,
			@Nullable String text,
			ResultExceptionHandler<Void, DbException> result);

}
