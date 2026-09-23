package org.briarproject.briar.attachment;

import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyStrengthener;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.api.attachment.InsufficientStorageException;
import org.jmock.Expectations;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;

import javax.annotation.Nullable;

import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.briarproject.briar.api.attachment.MediaConstants.MIN_FREE_SPACE_AFTER_FILE;

public class ChunkedFileStoreTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final ChunkedFileStore.Client client =
			context.mock(ChunkedFileStore.Client.class);

	/**
	 * Measured on a device: when a file used up the last free space, the
	 * commit failed part way, and a later start could not compact the
	 * database and refused to open. A file that would leave less than the
	 * reserve must be refused before anything is written.
	 */
	@Test(expected = InsufficientStorageException.class)
	public void testRefusesAFileThatWouldLeaveTooLittleSpace()
			throws Exception {
		byte[] file = getRandomBytes(1000);
		long free = MIN_FREE_SPACE_AFTER_FILE + file.length - 1;
		ChunkedFileStore store = new ChunkedFileStore(db, clientHelper,
				crypto, client, configWithFreeSpace(free));

		context.checking(new Expectations() {{
			// The file is hashed to learn its size; nothing is stored,
			// so the database must not be touched at all
			allowing(crypto).hash(with(any(String.class)),
					with(any(byte[][].class)));
			will(returnValue(getRandomBytes(32)));
		}});

		store.addLocalFile(new GroupId(getRandomId()), 1, "notes.txt",
				"text/plain", () -> new ByteArrayInputStream(file));
	}

	private DatabaseConfig configWithFreeSpace(long free) {
		File dir = new File(getTestDirectory(), "db") {
			@Override
			public long getUsableSpace() {
				return free;
			}
		};
		return new DatabaseConfig() {
			@Override
			public File getDatabaseDirectory() {
				return dir;
			}

			@Override
			public File getDatabaseKeyDirectory() {
				return dir;
			}

			@Nullable
			@Override
			public KeyStrengthener getKeyStrengthener() {
				return null;
			}
		};
	}
}
