package org.briarproject.briar.attachment;

import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.DbExpectations;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.junit.Test;

import java.io.ByteArrayInputStream;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static java.lang.System.arraycopy;
import static org.briarproject.bramble.test.TestUtils.getMessage;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.junit.Assert.assertArrayEquals;

public class AttachmentReaderImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final PrivateGroupManager privateGroupManager =
			context.mock(PrivateGroupManager.class);
	private final MessagingManager messagingManager =
			context.mock(MessagingManager.class);

	private final GroupId groupId = new GroupId(getRandomId());
	private final Group privateGroup = new Group(groupId,
			PrivateGroupManager.CLIENT_ID, PrivateGroupManager.MAJOR_VERSION,
			getRandomBytes(12));
	private final Group conversation = new Group(groupId,
			MessagingManager.CLIENT_ID, MessagingManager.MAJOR_VERSION,
			getRandomBytes(12));
	private final Message message = getMessage(groupId, 1234);
	private final String contentType = "image/jpeg";
	private final AttachmentHeader header = new AttachmentHeader(groupId,
			message.getId(), contentType);

	private final AttachmentReaderImpl attachmentReader =
			new AttachmentReaderImpl(db, clientHelper, privateGroupManager,
					messagingManager);

	@Test(expected = NoSuchMessageException.class)
	public void testWrongGroup() throws Exception {
		GroupId wrongGroupId = new GroupId(getRandomId());
		AttachmentHeader wrongGroup = new AttachmentHeader(wrongGroupId,
				message.getId(), contentType);

		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessage(txn, message.getId());
			will(returnValue(message));
		}});

		attachmentReader.getAttachment(wrongGroup);
	}

	@Test(expected = NoSuchMessageException.class)
	public void testMissingContentType() throws Exception {
		BdfDictionary meta = new BdfDictionary();

		testInvalidMetadata(meta);
	}

	@Test(expected = NoSuchMessageException.class)
	public void testWrongContentType() throws Exception {
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_CONTENT_TYPE, "image/png"));

		testInvalidMetadata(meta);
	}

	@Test(expected = NoSuchMessageException.class)
	public void testMissingDescriptorLengthAndNotAManifest() throws Exception {
		// Without a descriptor length the message might be the manifest of a
		// chunked file in a private group, but here it isn't one
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_CONTENT_TYPE, contentType));
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessage(txn, message.getId());
			will(returnValue(message));
			oneOf(clientHelper)
					.getMessageMetadataAsDictionary(txn, message.getId());
			will(returnValue(meta));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(privateGroup));
			oneOf(privateGroupManager).getFileHeader(txn, groupId,
					message.getId());
			will(throwException(new NoSuchMessageException()));
		}});

		attachmentReader.getAttachment(header);
	}

	@Test
	public void testReadsChunkedFileViaManifest() throws Exception {
		// A manifest has a content type but no descriptor length. The
		// attachment is read from the reassembled chunks.
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_CONTENT_TYPE, contentType));
		byte[] fileBytes = getRandomBytes(100_000);
		FileHeader file = new FileHeader(groupId, message.getId(),
				"image.jpg", contentType, fileBytes.length);
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessage(txn, message.getId());
			will(returnValue(message));
			oneOf(clientHelper)
					.getMessageMetadataAsDictionary(txn, message.getId());
			will(returnValue(meta));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(privateGroup));
			oneOf(privateGroupManager).getFileHeader(txn, groupId,
					message.getId());
			will(returnValue(file));
			oneOf(privateGroupManager).getFile(txn, file);
			will(returnValue(new ByteArrayInputStream(fileBytes)));
		}});

		Attachment attachment = attachmentReader.getAttachment(header);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyAndClose(attachment.getStream(), out);
		assertArrayEquals(fileBytes, out.toByteArray());
	}

	@Test
	public void testReadsChunkedFileInPrivateConversation() throws Exception {
		// The same lookup in a private conversation goes to the messaging
		// client, which owns that group
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_CONTENT_TYPE, contentType));
		byte[] fileBytes = getRandomBytes(100_000);
		FileHeader file = new FileHeader(groupId, message.getId(),
				"image.jpg", contentType, fileBytes.length);
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessage(txn, message.getId());
			will(returnValue(message));
			oneOf(clientHelper)
					.getMessageMetadataAsDictionary(txn, message.getId());
			will(returnValue(meta));
			oneOf(db).getGroup(txn, groupId);
			will(returnValue(conversation));
			oneOf(messagingManager).getFileHeader(txn, groupId,
					message.getId());
			will(returnValue(file));
			oneOf(messagingManager).getFile(txn, file);
			will(returnValue(new ByteArrayInputStream(fileBytes)));
		}});

		Attachment attachment = attachmentReader.getAttachment(header);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyAndClose(attachment.getStream(), out);
		assertArrayEquals(fileBytes, out.toByteArray());
	}

	private void testInvalidMetadata(BdfDictionary meta) throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessage(txn, message.getId());
			will(returnValue(message));
			oneOf(clientHelper)
					.getMessageMetadataAsDictionary(txn, message.getId());
			will(returnValue(meta));
		}});

		attachmentReader.getAttachment(header);
	}

	@Test
	public void testSkipsDescriptor() throws Exception {
		int descriptorLength = 123;
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_CONTENT_TYPE, contentType),
				new BdfEntry(MSG_KEY_DESCRIPTOR_LENGTH, descriptorLength));

		byte[] body = message.getBody();
		byte[] expectedData = new byte[body.length - descriptorLength];
		arraycopy(body, descriptorLength, expectedData, 0, expectedData.length);

		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessage(txn, message.getId());
			will(returnValue(message));
			oneOf(clientHelper)
					.getMessageMetadataAsDictionary(txn, message.getId());
			will(returnValue(meta));
		}});

		Attachment attachment = attachmentReader.getAttachment(header);
		InputStream in = attachment.getStream();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyAndClose(in, out);
		byte[] data = out.toByteArray();

		assertArrayEquals(expectedData, data);
	}
}
