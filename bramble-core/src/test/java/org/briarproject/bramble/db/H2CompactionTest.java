package org.briarproject.bramble.db;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.system.SystemClock;
import org.briarproject.bramble.test.BrambleTestCase;
import org.briarproject.bramble.test.TestDatabaseConfig;
import org.briarproject.bramble.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;

import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getSecretKey;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Compacting the database needs free space, and it is due on every open
 * after a shutdown that wasn't clean, which on Android includes the app
 * being killed. Measured on a device, a full phone made the compaction
 * fail and Briar refused to open at all, although nothing was lost. These
 * tests pin that opening never depends on compacting.
 */
public class H2CompactionTest extends BrambleTestCase {

	private static final String NAMESPACE = "test";

	private final SecretKey key = getSecretKey();
	private final File testDir = getTestDirectory();

	@Before
	public void setUp() {
		deleteTestDirectory(testDir);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	@Test
	public void testOpensWhenCompactionFails() throws Exception {
		storeValueAndCloseDirty();

		boolean[] attempted = {false};
		H2Database db = new H2Database(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock()) {
			@Override
			protected void compactAndClose() throws DbException {
				attempted[0] = true;
				throw new DbException();
			}
		};
		db.open(key, null);

		assertTrue(db.wasDirtyOnInitialisation());
		assertTrue(attempted[0]);
		assertEquals("kept", readValue(db));
		db.close();
	}

	@Test
	public void testSkipsCompactionWhenShortOfSpace() throws Exception {
		storeValueAndCloseDirty();

		H2Database db = new H2Database(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock()) {
			@Override
			protected boolean hasRoomToCompact() {
				return false;
			}

			@Override
			protected void compactAndClose() {
				fail("Compacted without room to do it");
			}
		};
		db.open(key, null);

		assertTrue(db.wasDirtyOnInitialisation());
		assertEquals("kept", readValue(db));
		db.close();
	}

	@Test
	public void testShutsDownCleanlyWithoutRoomToCompact() throws Exception {
		// Without room, a clean shutdown must still be clean, or the next
		// open would try to compact, and fail, all over again
		H2Database db = new H2Database(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock()) {
			@Override
			protected boolean hasRoomToCompact() {
				return false;
			}
		};
		db.open(key, null);
		db.close();

		H2Database reopened = new H2Database(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock());
		reopened.open(key, null);
		assertFalse(reopened.wasDirtyOnInitialisation());
		reopened.close();
	}

	/**
	 * Stores a value and then lets go of the database without the clean
	 * shutdown that marks it clean, as happens when Android kills the app.
	 */
	private void storeValueAndCloseDirty() throws Exception {
		H2Database db = new H2Database(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock());
		db.open(key, null);
		Connection txn = db.startTransaction();
		Settings s = new Settings();
		s.put("value", "kept");
		db.mergeSettings(txn, s, NAMESPACE);
		db.commitTransaction(txn);
		db.closeAllConnections();
	}

	private String readValue(H2Database db) throws Exception {
		Connection txn = db.startTransaction();
		String value = db.getSettings(txn, NAMESPACE).get("value");
		db.commitTransaction(txn);
		return value;
	}
}
