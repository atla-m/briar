package org.briarproject.bramble.api.sync.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * An event that is broadcast when a message is added to the database.
 */
@Immutable
@NotNullByDefault
public class MessageAddedEvent extends Event {

	private final Message message;
	@Nullable
	private final ContactId contactId;
	private final boolean validationRequired;

	public MessageAddedEvent(Message message, @Nullable ContactId contactId) {
		// A message from a contact needs validating; one we created
		// ourselves doesn't
		this(message, contactId, contactId != null);
	}

	public MessageAddedEvent(Message message, @Nullable ContactId contactId,
			boolean validationRequired) {
		this.message = message;
		this.contactId = contactId;
		this.validationRequired = validationRequired;
	}

	/**
	 * Returns the message that was added.
	 */
	public Message getMessage() {
		return message;
	}

	/**
	 * Returns the ID of the group to which the message belongs.
	 */
	public GroupId getGroupId() {
		return message.getGroupId();
	}

	/**
	 * Returns the ID of the contact from which the message was received, or
	 * null if the message was locally generated.
	 */
	@Nullable
	public ContactId getContactId() {
		return contactId;
	}

	/**
	 * Returns true if the message needs to be validated, which is the case
	 * for any message we did not create ourselves, whether it came from a
	 * contact or from somewhere else such as a published file.
	 */
	public boolean isValidationRequired() {
		return validationRequired;
	}
}
