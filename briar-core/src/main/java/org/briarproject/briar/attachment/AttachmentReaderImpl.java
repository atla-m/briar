package org.briarproject.briar.attachment;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import javax.inject.Inject;

import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;

public class AttachmentReaderImpl implements AttachmentReader {

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final PrivateGroupManager privateGroupManager;
	private final MessagingManager messagingManager;

	@Inject
	public AttachmentReaderImpl(DatabaseComponent db,
			ClientHelper clientHelper, PrivateGroupManager privateGroupManager,
			MessagingManager messagingManager) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.privateGroupManager = privateGroupManager;
		this.messagingManager = messagingManager;
	}

	@Override
	public Attachment getAttachment(AttachmentHeader h) throws DbException {
		return db.transactionWithResult(true, txn -> getAttachment(txn, h));
	}

	@Override
	public Attachment getAttachment(Transaction txn, AttachmentHeader h)
			throws DbException {
		MessageId m = h.getMessageId();
		Message message = clientHelper.getMessage(txn, m);
		// Check that the message is in the expected group, to prevent it from
		// being loaded in the context of a different group
		if (!message.getGroupId().equals(h.getGroupId())) {
			throw new NoSuchMessageException();
		}
		byte[] body = message.getBody();
		try {
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, m);
			String contentType = meta.getString(MSG_KEY_CONTENT_TYPE);
			if (!contentType.equals(h.getContentType()))
				throw new NoSuchMessageException();
			if (!meta.containsKey(MSG_KEY_DESCRIPTOR_LENGTH)) {
				// Not a single-message attachment. The header may point at
				// the manifest of a chunked image, which can be read once all
				// its chunks have arrived. The group tells us which client
				// owns the file.
				return new Attachment(h, getChunkedFile(txn, h));
			}
			int offset = meta.getInt(MSG_KEY_DESCRIPTOR_LENGTH);
			InputStream stream = new ByteArrayInputStream(body, offset,
					body.length - offset);
			return new Attachment(h, stream);
		} catch (FormatException e) {
			throw new NoSuchMessageException();
		}
	}

	private InputStream getChunkedFile(Transaction txn, AttachmentHeader h)
			throws DbException {
		ClientId client = db.getGroup(txn, h.getGroupId()).getClientId();
		if (client.equals(PrivateGroupManager.CLIENT_ID)) {
			FileHeader file = privateGroupManager.getFileHeader(txn,
					h.getGroupId(), h.getMessageId());
			return privateGroupManager.getFile(txn, file);
		} else if (client.equals(MessagingManager.CLIENT_ID)) {
			FileHeader file = messagingManager.getFileHeader(txn,
					h.getGroupId(), h.getMessageId());
			return messagingManager.getFile(txn, file);
		}
		throw new NoSuchMessageException();
	}

}
