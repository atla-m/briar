package org.briarproject.briar.privategroup;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.BdfMessageContext;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfReaderFactory;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.test.ValidatorTestCase;
import org.briarproject.briar.api.privategroup.MessageType;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory;
import org.jmock.Expectations;
import org.junit.Test;

import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.briarproject.bramble.test.TestUtils.getAuthor;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.util.StringUtils.getRandomString;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static org.briarproject.bramble.test.TestUtils.getMessage;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.briarproject.briar.api.privategroup.GroupMessageFactory.SIGNING_LABEL_JOIN;
import static org.briarproject.briar.api.privategroup.GroupMessageFactory.SIGNING_LABEL_POST;
import static org.briarproject.briar.api.privategroup.MessageType.ATTACHMENT;
import static org.briarproject.briar.api.privategroup.MessageType.FILE_CHUNK;
import static org.briarproject.briar.api.privategroup.MessageType.FILE_MANIFEST;
import static org.briarproject.briar.api.privategroup.MessageType.JOIN;
import static org.briarproject.briar.api.privategroup.MessageType.POST;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.FILE_CHUNK_PAYLOAD_LENGTH;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.GROUP_SALT_LENGTH;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_FILE_NAME_LENGTH;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_FILE_SIZE;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_NAME_LENGTH;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_POST_ATTACHMENTS;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_POST_TEXT_LENGTH;
import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory.SIGNING_LABEL_INVITE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FILE_CHUNK_IDS;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FILE_CONTENT_TYPE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FILE_NAME;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FILE_SIZE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_HAS_TEXT;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_INITIAL_JOIN_MSG;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_MEMBER;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PARENT_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PREVIOUS_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_READ;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_TIMESTAMP;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_TYPE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupMessageValidatorTest extends ValidatorTestCase {

	private final PrivateGroupFactory privateGroupFactory =
			context.mock(PrivateGroupFactory.class);
	private final GroupInvitationFactory groupInvitationFactory =
			context.mock(GroupInvitationFactory.class);
	private final BdfReaderFactory bdfReaderFactory =
			context.mock(BdfReaderFactory.class);
	private final BdfReader reader = context.mock(BdfReader.class);

	private final Author member = getAuthor();
	private final BdfList memberList = BdfList.of(
			member.getFormatVersion(),
			member.getName(),
			member.getPublicKey()
	);
	private final byte[] memberSignature = getRandomBytes(MAX_SIGNATURE_LENGTH);
	private final Author creator = getAuthor();
	private final BdfList creatorList = BdfList.of(
			creator.getFormatVersion(),
			creator.getName(),
			creator.getPublicKey()
	);
	private final byte[] creatorSignature =
			getRandomBytes(MAX_SIGNATURE_LENGTH);
	private final long inviteTimestamp = message.getTimestamp() - 1;
	private final BdfList invite =
			BdfList.of(inviteTimestamp, creatorSignature);
	private final PrivateGroup privateGroup = new PrivateGroup(group,
			getRandomString(MAX_GROUP_NAME_LENGTH), creator,
			getRandomBytes(GROUP_SALT_LENGTH));
	private final BdfList token = new BdfList();
	private final MessageId parentId = new MessageId(getRandomId());
	private final MessageId previousMsgId = new MessageId(getRandomId());
	private final String text = getRandomString(MAX_GROUP_POST_TEXT_LENGTH);
	private final String contentType = getRandomString(MAX_CONTENT_TYPE_BYTES);
	private final BdfList attachmentHeader =
			BdfList.of(new MessageId(getRandomId()), contentType);
	private final BdfList attachmentHeaders = BdfList.of(attachmentHeader);
	private final long now = message.getTimestamp() + 1000;
	// A file spanning four chunks
	private final String fileName = getRandomString(MAX_FILE_NAME_LENGTH);
	private final long fileSize = FILE_CHUNK_PAYLOAD_LENGTH * 3L + 12345;
	private final BdfList fileEntry = BdfList.of(new MessageId(getRandomId()),
			contentType, fileName, fileSize);
	private final BdfList chunkIds = BdfList.of(new MessageId(getRandomId()),
			new MessageId(getRandomId()), new MessageId(getRandomId()),
			new MessageId(getRandomId()));

	private final GroupMessageValidator validator =
			new GroupMessageValidator(privateGroupFactory, clientHelper,
					metadataEncoder, clock, groupInvitationFactory,
					bdfReaderFactory);

	// JOIN message

	@Test(expected = FormatException.class)
	public void testRejectsTooShortJoinMessage() throws Exception {
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsTooLongJoinMessage() throws Exception {
		expectParseAuthor(memberList, member);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite,
				memberSignature, "");
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsJoinWithNullAuthor() throws Exception {
		BdfList body = BdfList.of(JOIN.getInt(), null, invite, memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsJoinWithNonListAuthor() throws Exception {
		BdfList body = BdfList.of(JOIN.getInt(), 123, invite, memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsJoinWithInvalidAuthor() throws Exception {
		expectRejectAuthor(memberList);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsJoinWithNonListInvitation() throws Exception {
		expectParseAuthor(memberList, member);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, "not a list",
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsCreatorJoinWithTooShortMemberSignature()
			throws Exception {
		expectParseAuthor(creatorList, creator);
		BdfList body = BdfList.of(JOIN.getInt(), creatorList, invite,
				new byte[0]);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsCreatorJoinWithTooLongMemberSignature()
			throws Exception {
		expectParseAuthor(creatorList, creator);
		BdfList body = BdfList.of(JOIN.getInt(), creatorList, invite,
				getRandomBytes(MAX_SIGNATURE_LENGTH + 1));
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsCreatorJoinWithNullMemberSignature()
			throws Exception {
		expectParseAuthor(creatorList, creator);
		BdfList body = BdfList.of(JOIN.getInt(), creatorList, invite, null);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsCreatorJoinWithNonRawMemberSignature()
			throws Exception {
		expectParseAuthor(creatorList, creator);
		BdfList body = BdfList.of(JOIN.getInt(), creatorList, invite,
				"not raw");
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsCreatorJoinWithInvalidMemberSignature()
			throws Exception {
		expectCreatorJoinMessage(false);
		BdfList body = BdfList.of(JOIN.getInt(), creatorList, null,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test
	public void testAcceptsCreatorJoin() throws Exception {
		expectCreatorJoinMessage(true);
		BdfList body = BdfList.of(JOIN.getInt(), creatorList, null,
				memberSignature);
		BdfMessageContext messageContext =
				validator.validateMessage(message, group, body);
		assertExpectedMessageContext(messageContext, JOIN, creatorList,
				Collections.emptyList());
		assertTrue(messageContext.getDictionary()
				.getBoolean(KEY_INITIAL_JOIN_MSG));
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithNullInvitation() throws Exception {
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, null,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithTooShortInvitation() throws Exception {
		BdfList invalidInvite = BdfList.of(inviteTimestamp);
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithTooLongInvitation() throws Exception {
		BdfList invalidInvite =
				BdfList.of(inviteTimestamp, creatorSignature, "");
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithEqualInvitationTime()
			throws Exception {
		BdfList invalidInvite =
				BdfList.of(message.getTimestamp(), creatorSignature);
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithLaterInvitationTime()
			throws Exception {
		BdfList invalidInvite =
				BdfList.of(message.getTimestamp() + 1, creatorSignature);
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithNullInvitationTime() throws Exception {
		BdfList invalidInvite = BdfList.of(null, creatorSignature);
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithNonLongInvitationTime()
			throws Exception {
		BdfList invalidInvite = BdfList.of("not long", creatorSignature);
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithTooShortCreatorSignature()
			throws Exception {
		BdfList invalidInvite = BdfList.of(inviteTimestamp, new byte[0]);
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithTooLongCreatorSignature()
			throws Exception {
		BdfList invalidInvite = BdfList.of(inviteTimestamp,
				getRandomBytes(MAX_SIGNATURE_LENGTH + 1));
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithNullCreatorSignature()
			throws Exception {
		BdfList invalidInvite = BdfList.of(inviteTimestamp, null);
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithNonRawCreatorSignature()
			throws Exception {
		BdfList invalidInvite = BdfList.of(inviteTimestamp, "not raw");
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invalidInvite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithInvalidCreatorSignature()
			throws Exception {
		expectMemberJoinMessage(false, true);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithTooShortMemberSignature()
			throws Exception {
		expectParseAuthor(memberList, member);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite,
				new byte[0]);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithTooLongMemberSignature()
			throws Exception {
		expectParseAuthor(memberList, member);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite,
				getRandomBytes(MAX_SIGNATURE_LENGTH + 1));
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithNullMemberSignature()
			throws Exception {
		expectParseAuthor(memberList, member);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite, null);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithNonRawMemberSignature()
			throws Exception {
		expectParseAuthor(memberList, member);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite, "not raw");
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsMemberJoinWithInvalidMemberSignature()
			throws Exception {
		expectMemberJoinMessage(true, false);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite,
				memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test
	public void testAcceptsMemberJoin() throws Exception {
		expectMemberJoinMessage(true, true);
		BdfList body = BdfList.of(JOIN.getInt(), memberList, invite,
				memberSignature);
		BdfMessageContext messageContext =
				validator.validateMessage(message, group, body);
		assertExpectedMessageContext(messageContext, JOIN, memberList,
				Collections.emptyList());
		assertFalse(messageContext.getDictionary()
				.getBoolean(KEY_INITIAL_JOIN_MSG));
	}

	private void expectRejectAuthor(BdfList authorList) throws Exception {
		context.checking(new Expectations() {{
			oneOf(clientHelper).parseAndValidateAuthor(authorList);
			will(throwException(new FormatException()));
		}});
	}

	private void expectParsePrivateGroup() throws Exception {
		context.checking(new Expectations() {{
			oneOf(privateGroupFactory).parsePrivateGroup(group);
			will(returnValue(privateGroup));
		}});
	}

	private void expectCreatorJoinMessage(boolean memberSigValid)
			throws Exception {
		BdfList signed = BdfList.of(
				group.getId(),
				message.getTimestamp(),
				creatorList,
				null
		);
		expectParseAuthor(creatorList, creator);
		expectParsePrivateGroup();
		context.checking(new Expectations() {{
			oneOf(clientHelper).verifySignature(memberSignature,
					SIGNING_LABEL_JOIN, signed, creator.getPublicKey());
			if (!memberSigValid)
				will(throwException(new GeneralSecurityException()));
		}});
	}

	private void expectMemberJoinMessage(boolean creatorSigValid,
			boolean memberSigValid) throws Exception {
		BdfList signed = BdfList.of(
				group.getId(),
				message.getTimestamp(),
				memberList,
				invite
		);
		expectParseAuthor(memberList, member);
		expectParsePrivateGroup();
		context.checking(new Expectations() {{
			oneOf(groupInvitationFactory).createInviteToken(creator.getId(),
					member.getId(), privateGroup.getId(), inviteTimestamp);
			will(returnValue(token));
			oneOf(clientHelper).verifySignature(creatorSignature,
					SIGNING_LABEL_INVITE, token, creator.getPublicKey());
			if (!creatorSigValid) {
				will(throwException(new GeneralSecurityException()));
			} else {
				oneOf(clientHelper).verifySignature(memberSignature,
						SIGNING_LABEL_JOIN, signed, member.getPublicKey());
				if (!memberSigValid)
					will(throwException(new GeneralSecurityException()));
			}
		}});
	}

	// POST Message

	@Test(expected = FormatException.class)
	public void testRejectsTooShortPost() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsTooLongPost() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, attachmentHeaders, memberSignature, "");
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNullAuthor() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), null, parentId, previousMsgId,
				text, memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNonListAuthor() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), 123, parentId, previousMsgId,
				text, memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithInvalidAuthor() throws Exception {
		expectRejectAuthor(memberList);
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, memberSignature);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooShortParentId() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList,
				getRandomBytes(MessageId.LENGTH - 1), previousMsgId, text,
				memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooLongParentId() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList,
				getRandomBytes(MessageId.LENGTH + 1), previousMsgId, text,
				memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNonRawParentId() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, "not raw",
				previousMsgId, text, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooShortPreviousMsgId() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				getRandomBytes(MessageId.LENGTH - 1), text, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooLongPreviousMsgId() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				getRandomBytes(MessageId.LENGTH + 1), text, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNullPreviousMsgId() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId, null,
				text, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNonRawPreviousMsgId() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				"not raw", text, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooShortText() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, "", memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooLongText() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, getRandomString(MAX_GROUP_POST_TEXT_LENGTH + 1),
				memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNullText() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, null, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNonStringText() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, getRandomBytes(5), memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooShortSignature() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, new byte[0]);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooLongSignature() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text,
				getRandomBytes(MAX_SIGNATURE_LENGTH + 1));
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNullSignature() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, null);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNonRawSignature() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, "not raw");
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithInvalidSignature() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, memberSignature);
		expectPostMessage(parentId, false);
		validator.validateMessage(message, group, body);
	}

	@Test
	public void testAcceptsPost() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, memberSignature);
		expectPostMessage(parentId, true);
		BdfMessageContext messageContext =
				validator.validateMessage(message, group, body);
		assertExpectedMessageContext(messageContext, POST, memberList,
				Arrays.asList(parentId, previousMsgId));
		assertArrayEquals(previousMsgId.getBytes(),
				messageContext.getDictionary().getRaw(KEY_PREVIOUS_MSG_ID));
		assertArrayEquals(parentId.getBytes(),
				messageContext.getDictionary().getRaw(KEY_PARENT_MSG_ID));
	}

	@Test
	public void testAcceptsTopLevelPost() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, memberSignature);
		expectPostMessage(null, true);
		BdfMessageContext messageContext =
				validator.validateMessage(message, group, body);
		assertExpectedMessageContext(messageContext, POST, memberList,
				Collections.singletonList(previousMsgId));
		assertArrayEquals(previousMsgId.getBytes(),
				messageContext.getDictionary().getRaw(KEY_PREVIOUS_MSG_ID));
		assertFalse(
				messageContext.getDictionary().containsKey(KEY_PARENT_MSG_ID));
	}



	// POST with attachments (client version 0.1)

	@Test
	public void testAcceptsPostWithAttachments() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, attachmentHeaders, memberSignature);
		expectPostWithAttachments(parentId, text, attachmentHeaders, true);
		BdfMessageContext messageContext =
				validator.validateMessage(message, group, body);
		assertExpectedMessageContext(messageContext, POST, memberList,
				Arrays.asList(parentId, previousMsgId));
		BdfDictionary meta = messageContext.getDictionary();
		assertTrue(meta.getBoolean(KEY_HAS_TEXT));
		assertEquals(attachmentHeaders, meta.getList(KEY_ATTACHMENT_HEADERS));
	}

	@Test
	public void testAcceptsPostWithAttachmentsAndNullText() throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, null, attachmentHeaders, memberSignature);
		expectPostWithAttachments(null, null, attachmentHeaders, true);
		BdfMessageContext messageContext =
				validator.validateMessage(message, group, body);
		assertExpectedMessageContext(messageContext, POST, memberList,
				Collections.singletonList(previousMsgId));
		BdfDictionary meta = messageContext.getDictionary();
		assertFalse(meta.getBoolean(KEY_HAS_TEXT));
		assertEquals(attachmentHeaders, meta.getList(KEY_ATTACHMENT_HEADERS));
	}

	@Test
	public void testAcceptsPostWithMaxAttachments() throws Exception {
		BdfList headers = new BdfList();
		for (int i = 0; i < MAX_GROUP_POST_ATTACHMENTS; i++) {
			headers.add(BdfList.of(new MessageId(getRandomId()), contentType));
		}
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectPostWithAttachments(null, text, headers, true);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooManyAttachments() throws Exception {
		BdfList headers = new BdfList();
		for (int i = 0; i < MAX_GROUP_POST_ATTACHMENTS + 1; i++) {
			headers.add(BdfList.of(new MessageId(getRandomId()), contentType));
		}
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithEmptyAttachmentList() throws Exception {
		// The format with attachment headers is only used when there are
		// attachments, so an empty list isn't a valid encoding
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, new BdfList(), memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithNonListAttachmentHeaders()
			throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, "not a list", memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooShortAttachmentHeader()
			throws Exception {
		BdfList headers = BdfList.of(BdfList.of(new MessageId(getRandomId())));
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooShortAttachmentId() throws Exception {
		BdfList headers = BdfList.of(BdfList.of(
				getRandomBytes(MessageId.LENGTH - 1), contentType));
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooLongAttachmentContentType()
			throws Exception {
		BdfList headers = BdfList.of(BdfList.of(new MessageId(getRandomId()),
				getRandomString(MAX_CONTENT_TYPE_BYTES + 1)));
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithAttachmentsAndInvalidSignature()
			throws Exception {
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, attachmentHeaders, memberSignature);
		expectPostWithAttachments(parentId, text, attachmentHeaders, false);
		validator.validateMessage(message, group, body);
	}


	// POST with a shared file (four-element header entry)

	@Test
	public void testAcceptsPostWithFile() throws Exception {
		BdfList headers = BdfList.of(fileEntry);
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectPostWithAttachments(null, text, headers, true);
		BdfMessageContext messageContext =
				validator.validateMessage(message, group, body);
		assertEquals(headers,
				messageContext.getDictionary().getList(KEY_ATTACHMENT_HEADERS));
	}

	@Test
	public void testAcceptsPostWithImageAndFile() throws Exception {
		BdfList headers = BdfList.of(attachmentHeader, fileEntry);
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, null, headers, memberSignature);
		expectPostWithAttachments(null, null, headers, true);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithThreeElementHeaderEntry() throws Exception {
		BdfList headers = BdfList.of(BdfList.of(new MessageId(getRandomId()),
				contentType, fileName));
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooLongFileName() throws Exception {
		BdfList headers = BdfList.of(BdfList.of(new MessageId(getRandomId()),
				contentType, getRandomString(MAX_FILE_NAME_LENGTH + 1),
				fileSize));
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithZeroFileSize() throws Exception {
		BdfList headers = BdfList.of(BdfList.of(new MessageId(getRandomId()),
				contentType, fileName, 0L));
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsPostWithTooBigFileSize() throws Exception {
		BdfList headers = BdfList.of(BdfList.of(new MessageId(getRandomId()),
				contentType, fileName, MAX_GROUP_FILE_SIZE + 1));
		BdfList body = BdfList.of(POST.getInt(), memberList, null,
				previousMsgId, text, headers, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}

	// FILE_MANIFEST message

	@Test
	public void testAcceptsFileManifest() throws Exception {
		BdfList body = BdfList.of(FILE_MANIFEST.getInt(), fileName,
				contentType, fileSize, chunkIds);
		BdfMessageContext messageContext =
				validator.validateMessage(message, group, body);
		BdfDictionary meta = messageContext.getDictionary();
		assertEquals(FILE_MANIFEST.getInt(), meta.getInt(KEY_TYPE).intValue());
		assertEquals(message.getTimestamp(),
				meta.getLong(KEY_TIMESTAMP).longValue());
		assertEquals(fileName, meta.getString(KEY_FILE_NAME));
		assertEquals(contentType, meta.getString(KEY_FILE_CONTENT_TYPE));
		assertEquals(contentType, meta.getString(MSG_KEY_CONTENT_TYPE));
		assertEquals(fileSize, meta.getLong(KEY_FILE_SIZE).longValue());
		assertEquals(chunkIds, meta.getList(KEY_FILE_CHUNK_IDS));
		assertEquals(Collections.emptyList(), messageContext.getDependencies());
	}

	@Test(expected = FormatException.class)
	public void testRejectsFileManifestWithWrongChunkCount() throws Exception {
		// The size implies four chunks, but only three are listed
		BdfList threeIds = BdfList.of(new MessageId(getRandomId()),
				new MessageId(getRandomId()), new MessageId(getRandomId()));
		BdfList body = BdfList.of(FILE_MANIFEST.getInt(), fileName,
				contentType, fileSize, threeIds);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsFileManifestWithDuplicateChunkIds()
			throws Exception {
		MessageId dupe = new MessageId(getRandomId());
		BdfList dupes = BdfList.of(dupe, new MessageId(getRandomId()), dupe,
				new MessageId(getRandomId()));
		BdfList body = BdfList.of(FILE_MANIFEST.getInt(), fileName,
				contentType, fileSize, dupes);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsFileManifestWithTooLongName() throws Exception {
		BdfList body = BdfList.of(FILE_MANIFEST.getInt(),
				getRandomString(MAX_FILE_NAME_LENGTH + 1), contentType,
				fileSize, chunkIds);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsFileManifestWithTooBigSize() throws Exception {
		BdfList body = BdfList.of(FILE_MANIFEST.getInt(), fileName,
				contentType, MAX_GROUP_FILE_SIZE + 1, chunkIds);
		validator.validateMessage(message, group, body);
	}

	@Test(expected = FormatException.class)
	public void testRejectsFileManifestWithTooShortChunkId() throws Exception {
		BdfList shortIds = BdfList.of(getRandomBytes(MessageId.LENGTH - 1),
				new MessageId(getRandomId()), new MessageId(getRandomId()),
				new MessageId(getRandomId()));
		BdfList body = BdfList.of(FILE_MANIFEST.getInt(), fileName,
				contentType, fileSize, shortIds);
		validator.validateMessage(message, group, body);
	}

	// FILE_CHUNK message, validated from the raw body like an attachment

	@Test
	public void testAcceptsFileChunk() throws Exception {
		Message chunk = getMessage(groupId, 1000);
		expectCheckTimestamp(now);
		expectParseList(BdfList.of(FILE_CHUNK.getInt()));
		// Descriptor length is zero as the test doesn't read from the
		// counting input stream
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(KEY_TYPE, FILE_CHUNK.getInt()),
				new BdfEntry(KEY_TIMESTAMP, chunk.getTimestamp()),
				new BdfEntry(MSG_KEY_DESCRIPTOR_LENGTH, 0L)
		);
		expectEncodeMetadata(meta);
		validator.validateMessage(chunk, group);
	}

	@Test(expected = InvalidMessageException.class)
	public void testRejectsFileChunkWithTooLongPayload() throws Exception {
		// With a zero-length descriptor the whole body is payload, and a
		// maximum-length body exceeds the chunk payload length
		Message chunk = getMessage(groupId, MAX_MESSAGE_BODY_LENGTH);
		expectCheckTimestamp(now);
		expectParseList(BdfList.of(FILE_CHUNK.getInt()));
		validator.validateMessage(chunk, group);
	}

	@Test(expected = InvalidMessageException.class)
	public void testRejectsFileChunkWithTooLongDescriptor() throws Exception {
		Message chunk = getMessage(groupId, 1000);
		expectCheckTimestamp(now);
		expectParseList(BdfList.of(FILE_CHUNK.getInt(), 1));
		validator.validateMessage(chunk, group);
	}

	// ATTACHMENT message, which is validated from the raw body because the
	// descriptor is followed by the attachment's bytes

	@Test(expected = InvalidMessageException.class)
	public void testRejectsFarFutureTimestamp() throws Exception {
		expectCheckTimestamp(message.getTimestamp() - MAX_CLOCK_DIFFERENCE - 1);
		validator.validateMessage(message, group);
	}

	@Test(expected = InvalidMessageException.class)
	public void testRejectsEmptyBody() throws Exception {
		expectCheckTimestamp(now);
		expectParseList(new BdfList());
		validator.validateMessage(message, group);
	}

	@Test(expected = InvalidMessageException.class)
	public void testRejectsTrailingDataForPost() throws Exception {
		// Only attachments may have data after the list
		BdfList body = BdfList.of(POST.getInt(), memberList, parentId,
				previousMsgId, text, memberSignature);
		expectCheckTimestamp(now);
		expectParseList(body);
		expectReadEof(false);
		validator.validateMessage(message, group);
	}

	@Test
	public void testAcceptsValidDescriptorForAttachment() throws Exception {
		expectCheckTimestamp(now);
		expectParseList(BdfList.of(ATTACHMENT.getInt(), contentType));
		// Descriptor length is zero as the test doesn't read from the
		// counting input stream
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(KEY_TYPE, ATTACHMENT.getInt()),
				new BdfEntry(KEY_TIMESTAMP, message.getTimestamp()),
				new BdfEntry(MSG_KEY_DESCRIPTOR_LENGTH, 0L),
				new BdfEntry(MSG_KEY_CONTENT_TYPE, contentType)
		);
		expectEncodeMetadata(meta);
		validator.validateMessage(message, group);
	}

	@Test(expected = InvalidMessageException.class)
	public void testRejectsTooShortDescriptorForAttachment() throws Exception {
		expectCheckTimestamp(now);
		expectParseList(BdfList.of(ATTACHMENT.getInt()));
		validator.validateMessage(message, group);
	}

	@Test(expected = InvalidMessageException.class)
	public void testRejectsTooLongDescriptorForAttachment() throws Exception {
		expectCheckTimestamp(now);
		expectParseList(BdfList.of(ATTACHMENT.getInt(), contentType, 123));
		validator.validateMessage(message, group);
	}

	@Test(expected = InvalidMessageException.class)
	public void testRejectsNullContentTypeForAttachment() throws Exception {
		expectCheckTimestamp(now);
		expectParseList(BdfList.of(ATTACHMENT.getInt(), null));
		validator.validateMessage(message, group);
	}

	@Test(expected = InvalidMessageException.class)
	public void testRejectsTooLongContentTypeForAttachment() throws Exception {
		expectCheckTimestamp(now);
		expectParseList(BdfList.of(ATTACHMENT.getInt(),
				getRandomString(MAX_CONTENT_TYPE_BYTES + 1)));
		validator.validateMessage(message, group);
	}

	private void expectCheckTimestamp(long now) {
		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
		}});
	}

	private void expectParseList(BdfList body) throws Exception {
		context.checking(new Expectations() {{
			oneOf(bdfReaderFactory).createReader(with(any(InputStream.class)),
					with(true));
			will(returnValue(reader));
			oneOf(reader).readList();
			will(returnValue(body));
		}});
	}

	private void expectReadEof(boolean eof) throws Exception {
		context.checking(new Expectations() {{
			oneOf(reader).eof();
			will(returnValue(eof));
		}});
	}

	private void expectEncodeMetadata(BdfDictionary meta) throws Exception {
		context.checking(new Expectations() {{
			oneOf(metadataEncoder).encode(meta);
			will(returnValue(new Metadata()));
		}});
	}

	private void expectPostWithAttachments(MessageId parentId, String text,
			BdfList headers, boolean sigValid) throws Exception {
		BdfList signed = BdfList.of(
				group.getId(),
				message.getTimestamp(),
				memberList,
				parentId == null ? null : parentId.getBytes(),
				previousMsgId.getBytes(),
				text,
				headers
		);
		expectParseAuthor(memberList, member);
		context.checking(new Expectations() {{
			oneOf(clientHelper).verifySignature(memberSignature,
					SIGNING_LABEL_POST, signed, member.getPublicKey());
			if (!sigValid)
				will(throwException(new GeneralSecurityException()));
		}});
	}

	private void expectPostMessage(MessageId parentId, boolean sigValid)
			throws Exception {
		BdfList signed = BdfList.of(
				group.getId(),
				message.getTimestamp(),
				memberList,
				parentId == null ? null : parentId.getBytes(),
				previousMsgId.getBytes(),
				text
		);
		expectParseAuthor(memberList, member);
		context.checking(new Expectations() {{
			oneOf(clientHelper).verifySignature(memberSignature,
					SIGNING_LABEL_POST, signed, member.getPublicKey());
			if (!sigValid)
				will(throwException(new GeneralSecurityException()));
		}});
	}

	private void assertExpectedMessageContext(BdfMessageContext c,
			MessageType type, BdfList member,
			Collection<MessageId> dependencies) throws FormatException {
		BdfDictionary d = c.getDictionary();
		assertEquals(type.getInt(), d.getInt(KEY_TYPE).intValue());
		assertEquals(message.getTimestamp(),
				d.getLong(KEY_TIMESTAMP).longValue());
		assertFalse(d.getBoolean(KEY_READ));
		assertEquals(member, d.getList(KEY_MEMBER));
		assertEquals(dependencies, c.getDependencies());
	}

	@Test(expected = InvalidMessageException.class)
	public void testRejectsMessageWithUnknownType() throws Exception {
		BdfList body = BdfList.of(POST.getInt() + 1, memberList,
				parentId, previousMsgId, text, memberSignature);
		expectParseAuthor(memberList, member);
		validator.validateMessage(message, group, body);
	}
}
