package org.briarproject.briar.android.forward;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.android.contactselection.ContactSelectorController;
import org.briarproject.briar.android.contactselection.SelectableContactItem;
import org.briarproject.briar.android.controller.handler.ResultExceptionHandler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

@NotNullByDefault
public interface ForwardPostController
		extends ContactSelectorController<SelectableContactItem> {

	/**
	 * Sends the given channel post to each of the given contacts as a
	 * private message carrying the channel's link.
	 */
	void forward(GroupId blogId, MessageId postId,
			Collection<ContactId> contacts,
			ResultExceptionHandler<Void, DbException> handler);
}
