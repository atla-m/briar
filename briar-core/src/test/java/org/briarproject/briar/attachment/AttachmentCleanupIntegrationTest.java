package org.briarproject.briar.attachment;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.test.TestDatabaseConfigModule;
import org.briarproject.bramble.system.TimeTravelModule;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.privategroup.GroupMessage;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.test.BriarIntegrationTest;
import org.briarproject.briar.test.BriarIntegrationTestComponent;
import org.briarproject.briar.test.DaggerBriarIntegrationTestComponent;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.briarproject.bramble.api.cleanup.CleanupManager.BATCH_DELAY_MS;
import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.briar.api.attachment.MediaConstants.FILE_CHUNK_PAYLOAD_LENGTH;
import static org.briarproject.briar.api.blog.BlogConstants.MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
import static org.briarproject.briar.attachment.ChunkedFileStore.KEY_FILE_MANIFEST_ID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A manifest or chunk that arrives without a post to reference it is
 * given a cleanup timer. When the timer fires, the cleanup manager hands
 * the message to the client's cleanup hook, and a client without one
 * makes it throw on the database thread, every time the database opens.
 * These tests let the timer fire and check the file is gone.
 */
public class AttachmentCleanupIntegrationTest
		extends BriarIntegrationTest<BriarIntegrationTestComponent> {

	private final long startTime = System.currentTimeMillis();

	private PrivateGroupManager groupManager0, groupManager1;
	private BlogManager blogManager0, blogManager1;

	@Before
	@Override
	public void setUp() throws Exception {
		super.setUp();
		groupManager0 = c0.getPrivateGroupManager();
		groupManager1 = c1.getPrivateGroupManager();
		blogManager0 = c0.getBlogManager();
		blogManager1 = c1.getBlogManager();
		// Run the initial cleanup task that was scheduled at startup
		c0.getTimeTravel().addCurrentTimeMillis(BATCH_DELAY_MS);
		c1.getTimeTravel().addCurrentTimeMillis(BATCH_DELAY_MS);
		c2.getTimeTravel().addCurrentTimeMillis(BATCH_DELAY_MS);
	}

	@Override
	protected void createComponents() {
		BriarIntegrationTestComponent component =
				DaggerBriarIntegrationTestComponent.builder().build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(component);
		component.inject(this);

		c0 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t0Dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c0);

		c1 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t1Dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c1);

		c2 = DaggerBriarIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(t2Dir))
				.timeTravelModule(new TimeTravelModule(true))
				.build();
		BriarIntegrationTestComponent.Helper.injectEagerSingletons(c2);

		try {
			c0.getTimeTravel().setCurrentTimeMillis(startTime);
			c1.getTimeTravel().setCurrentTimeMillis(startTime + 1);
			c2.getTimeTravel().setCurrentTimeMillis(startTime + 2);
		} catch (InterruptedException e) {
			fail();
		}
	}

	@Test
	public void testAnUnreferencedGroupFileIsDeletedWhenItsTimerFires()
			throws Exception {
		PrivateGroup pg =
				privateGroupFactory.createPrivateGroup("Testgroup", author0);
		joinGroup(pg);
		GroupId g = pg.getId();

		// author0 stores a two-chunk file and shares it without a post
		byte[] bytes = getRandomBytes(FILE_CHUNK_PAYLOAD_LENGTH + 1);
		FileHeader file = groupManager0.addLocalFile(g,
				c0.getClock().currentTimeMillis(), "a.bin",
				"application/octet-stream",
				() -> new ByteArrayInputStream(bytes));
		shareByHand(file);
		sync0To1(3, true);
		FileStatus status = groupManager1.getFileStatus(file);
		assertTrue(status.isComplete());

		// Just before the timer fires the file is still there
		long latency = MISSING_ATTACHMENT_CLEANUP_DURATION_MS + BATCH_DELAY_MS;
		c1.getTimeTravel().addCurrentTimeMillis(latency - 1);
		assertTrue(groupManager1.getFileStatus(file).isComplete());

		// When it fires, the manifest and its chunks are gone
		c1.getTimeTravel().addCurrentTimeMillis(1);
		status = groupManager1.getFileStatus(file);
		assertFalse(status.isManifestReceived());
		assertEquals(0, status.getChunksReceived());
		assertEquals(0, countChunks(c1, file));
	}

	@Test
	public void testAnUnreferencedBlogFileIsDeletedWhenItsTimerFires()
			throws Exception {
		Blog blog = blogManager0.getPersonalBlog(author0);
		GroupId g = blog.getId();

		byte[] bytes = getRandomBytes(FILE_CHUNK_PAYLOAD_LENGTH + 1);
		FileHeader file = blogManager0.addLocalFile(g,
				c0.getClock().currentTimeMillis(), "a.bin",
				"application/octet-stream",
				() -> new ByteArrayInputStream(bytes));
		shareByHand(file);
		sync0To1(3, true);
		assertTrue(blogManager1.getFileStatus(file).isComplete());

		long latency = MISSING_ATTACHMENT_CLEANUP_DURATION_MS + BATCH_DELAY_MS;
		c1.getTimeTravel().addCurrentTimeMillis(latency - 1);
		assertTrue(blogManager1.getFileStatus(file).isComplete());

		c1.getTimeTravel().addCurrentTimeMillis(1);
		FileStatus status = blogManager1.getFileStatus(file);
		assertFalse(status.isManifestReceived());
		assertEquals(0, countChunks(c1, file));
	}

	private void shareByHand(FileHeader file) throws Exception {
		db0.transaction(false, txn -> {
			db0.setMessageShared(txn, file.getManifestId());
			for (MessageId id : chunkIds(c0, file, txn).keySet()) {
				db0.setMessageShared(txn, id);
			}
		});
	}

	private int countChunks(BriarIntegrationTestComponent c, FileHeader file)
			throws Exception {
		return c.getDatabaseComponent().transactionWithResult(true,
				txn -> chunkIds(c, file, txn).size());
	}

	private Map<MessageId, BdfDictionary> chunkIds(
			BriarIntegrationTestComponent c, FileHeader file,
			org.briarproject.bramble.api.db.Transaction txn)
			throws Exception {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(KEY_FILE_MANIFEST_ID, file.getManifestId()));
		return c.getClientHelper().getMessageMetadataAsDictionary(txn,
				file.getGroupId(), query);
	}

	private void joinGroup(PrivateGroup pg) throws Exception {
		GroupId g = pg.getId();
		long joinTime = c0.getClock().currentTimeMillis();
		GroupMessage joinMsg0 = groupMessageFactory
				.createJoinMessage(g, joinTime, author0);
		groupManager0.addPrivateGroup(pg, joinMsg0, true);
		db0.transaction(false, txn -> db0.setGroupVisibility(txn,
				contactId1From0, g, SHARED));
		joinTime = c1.getClock().currentTimeMillis();
		long inviteTime = joinTime - 1;
		Contact c1From0 = contactManager0.getContact(contactId1From0);
		byte[] creatorSignature = groupInvitationFactory
				.signInvitation(c1From0, g, inviteTime, author0.getPrivateKey());
		GroupMessage joinMsg1 = groupMessageFactory
				.createJoinMessage(g, joinTime, author1, inviteTime,
						creatorSignature);
		groupManager1.addPrivateGroup(pg, joinMsg1, false);
		db1.transaction(false, txn -> db1.setGroupVisibility(txn,
				contactId0From1, g, SHARED));
		sync0To1(1, true);
		sync1To0(1, true);
	}
}
