package org.briarproject.briar.channel;

import org.briarproject.bramble.api.Cancellable;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.keyagreement.KeyAgreementConnection;
import org.briarproject.bramble.api.keyagreement.KeyAgreementListener;
import org.briarproject.bramble.api.plugin.BluetoothConstants;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.DbExpectations;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.channel.event.ChannelNearbyEvent;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.test.TestUtils.getAuthor;
import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.briar.api.blog.BlogManager.CLIENT_ID;
import static org.briarproject.briar.api.blog.BlogManager.MAJOR_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NearbyChannelSharerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final BlogManager blogManager = context.mock(BlogManager.class);
	private final PluginManager pluginManager =
			context.mock(PluginManager.class);
	private final DuplexPlugin plugin = context.mock(DuplexPlugin.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final TaskScheduler scheduler = context.mock(TaskScheduler.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final Clock clock = context.mock(Clock.class);
	private final Cancellable scan = context.mock(Cancellable.class, "scan");
	private final Cancellable timer = context.mock(Cancellable.class, "timer");

	// The accept loop isn't run: the test is about the session's lifetime
	private final Executor ioExecutor = r -> {
	};

	private final Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Blog blog = new Blog(group, getAuthor(), false, true);
	private final Transaction txn = new Transaction(null, true);

	public NearbyChannelSharerImplTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
	}

	@Test
	public void testRemovingTheChannelStopsSharingItNearby()
			throws Exception {
		KeyAgreementListener listener = new KeyAgreementListener(
				new BdfList()) {
			private boolean closed = false;

			@Override
			public KeyAgreementConnection accept() {
				throw new AssertionError();
			}

			@Override
			public void close() {
				assertTrue(!closed);
				closed = true;
			}
		};
		NearbyChannelSharerImpl sharer = new NearbyChannelSharerImpl(db,
				blogManager, context.mock(NearbyChannelProtocol.class),
				pluginManager, crypto, scheduler, ioExecutor, eventBus, clock);

		context.checking(new DbExpectations() {{
			oneOf(pluginManager).getPlugin(BluetoothConstants.ID);
			will(returnValue(plugin));
			oneOf(plugin).getState();
			will(returnValue(ACTIVE));
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(blogManager).getBlog(txn, group.getId());
			will(returnValue(blog));
			oneOf(crypto).hash(with(any(String.class)),
					with(any(byte[][].class)));
			will(returnValue(getRandomId()));
			oneOf(plugin).createKeyAgreementListener(
					with(any(byte[].class)));
			will(returnValue(listener));
			allowing(clock).currentTimeMillis();
			will(returnValue(1000L));
			oneOf(scheduler).scheduleWithFixedDelay(with(any(Runnable.class)),
					with(ioExecutor), with(any(long.class)),
					with(any(long.class)), with(any(TimeUnit.class)));
			will(returnValue(scan));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(any(long.class)),
					with(any(TimeUnit.class)));
			will(returnValue(timer));
			oneOf(eventBus).broadcast(with(any(ChannelNearbyEvent.class)));
		}});
		assertTrue(sharer.setSharingNearby(group.getId(), true));
		assertTrue(sharer.getNearbyExpiry(group.getId()) > 0);

		context.checking(new Expectations() {{
			oneOf(scan).cancel();
			oneOf(timer).cancel();
			oneOf(eventBus).broadcast(with(any(ChannelNearbyEvent.class)));
		}});
		sharer.eventOccurred(new GroupRemovedEvent(group));
		assertEquals(0, sharer.getNearbyExpiry(group.getId()));
	}
}
