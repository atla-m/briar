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
import org.briarproject.bramble.api.plugin.TransportConnectionReader;
import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.bramble.test.BrambleTestCase;
import org.briarproject.bramble.test.DbExpectations;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.channel.event.ChannelNearbyEvent;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.jmock.integration.junit4.JUnitRuleMockery;
import org.jmock.lib.action.CustomAction;
import org.jmock.lib.concurrent.Synchroniser;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.test.TestUtils.getAuthor;
import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.briar.api.blog.BlogManager.CLIENT_ID;
import static org.briarproject.briar.api.blog.BlogManager.MAJOR_VERSION;
import static org.briarproject.briar.api.channel.ChannelNearbyManager.NEARBY_DURATION_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NearbyChannelSharerImplTest extends BrambleTestCase {

	@Rule
	public JUnitRuleMockery context = new JUnitRuleMockery() {{
		// So we can mock the protocol, a class, from the accept loop
		setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		setThreadingPolicy(new Synchroniser());
	}};

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final BlogManager blogManager = context.mock(BlogManager.class);
	private final NearbyChannelProtocol protocol =
			context.mock(NearbyChannelProtocol.class);
	private final PluginManager pluginManager =
			context.mock(PluginManager.class);
	private final DuplexPlugin plugin = context.mock(DuplexPlugin.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final TaskScheduler scheduler = context.mock(TaskScheduler.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final Clock clock = context.mock(Clock.class);
	private final Cancellable scan = context.mock(Cancellable.class, "scan");
	private final Cancellable timer = context.mock(Cancellable.class, "timer");

	private final AtomicLong now = new AtomicLong(1000);
	private final Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
	private final Blog blog = new Blog(group, getAuthor(), false, true);
	private final Transaction txn = new Transaction(null, true);

	@Test
	public void testRemovingTheChannelStopsSharingItNearby()
			throws Exception {
		// The accept loop isn't run: the test is about the session
		NearbyChannelSharerImpl sharer = startSharing(r -> {
		}, new FakeListener(null));

		context.checking(new Expectations() {{
			oneOf(scan).cancel();
			oneOf(timer).cancel();
			oneOf(eventBus).broadcast(with(any(ChannelNearbyEvent.class)));
		}});
		sharer.eventOccurred(new GroupRemovedEvent(group));
		assertEquals(0, sharer.getNearbyExpiry(group.getId()));
	}

	@Test
	public void testASessionPastItsHourEndsWhenLookedAt() throws Exception {
		// Doze can make the timer that ends a session late, while the
		// switch counts down by the clock. Asking after the hour is up
		// ends the session, so the phone doesn't share while it shows off.
		NearbyChannelSharerImpl sharer = startSharing(r -> {
		}, new FakeListener(null));
		long expiry = sharer.getNearbyExpiry(group.getId());
		now.set(expiry);

		context.checking(new Expectations() {{
			oneOf(scan).cancel();
			oneOf(timer).cancel();
			oneOf(eventBus).broadcast(with(any(ChannelNearbyEvent.class)));
		}});
		assertEquals(0, sharer.getNearbyExpiry(group.getId()));
		// Asking again finds nothing to end
		assertEquals(0, sharer.getNearbyExpiry(group.getId()));
	}

	@Test
	public void testTurningSharingOffEndsAnExchangeInProgress()
			throws Exception {
		// A stranger connects and goes quiet. Turning sharing off must
		// close their connection, not only stop accepting new ones.
		CountDownLatch serving = new CountDownLatch(1);
		CountDownLatch closed = new CountDownLatch(1);
		FakeConnection conn = new FakeConnection(closed);
		Cancellable deadline = context.mock(Cancellable.class, "deadline");
		context.checking(new Expectations() {{
			// The exchange's own deadline, ten minutes
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(any(Executor.class)), with(10 * 60 * 1000L),
					with(any(TimeUnit.class)));
			will(returnValue(deadline));
			oneOf(protocol).serve(with(group.getId()),
					with(any(InputStream.class)),
					with(any(OutputStream.class)));
			will(new CustomAction("wait for the connection to close") {
				@Override
				public Object invoke(org.jmock.api.Invocation i)
						throws Throwable {
					serving.countDown();
					// Generous: the whole suite may be running alongside
					if (!closed.await(60, SECONDS)) throw new AssertionError();
					throw new IOException("Closed");
				}
			});
			oneOf(deadline).cancel();
		}});
		NearbyChannelSharerImpl sharer = startSharing(
				r -> new Thread(r).start(), new FakeListener(conn));
		assertTrue(serving.await(5, SECONDS));

		context.checking(new Expectations() {{
			oneOf(scan).cancel();
			oneOf(timer).cancel();
			oneOf(eventBus).broadcast(with(any(ChannelNearbyEvent.class)));
		}});
		sharer.setSharingNearby(group.getId(), false);
		assertTrue(closed.await(5, SECONDS));
	}

	private NearbyChannelSharerImpl startSharing(Executor ioExecutor,
			KeyAgreementListener listener) throws Exception {
		NearbyChannelSharerImpl sharer = new NearbyChannelSharerImpl(db,
				blogManager, protocol, pluginManager, crypto, scheduler,
				ioExecutor, eventBus, clock);
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
			will(new CustomAction("read the clock") {
				@Override
				public Object invoke(org.jmock.api.Invocation i) {
					return now.get();
				}
			});
			oneOf(scheduler).scheduleWithFixedDelay(with(any(Runnable.class)),
					with(ioExecutor), with(any(long.class)),
					with(any(long.class)), with(any(TimeUnit.class)));
			will(returnValue(scan));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(NEARBY_DURATION_MS),
					with(any(TimeUnit.class)));
			will(returnValue(timer));
			oneOf(eventBus).broadcast(with(any(ChannelNearbyEvent.class)));
		}});
		assertTrue(sharer.setSharingNearby(group.getId(), true));
		assertTrue(sharer.getNearbyExpiry(group.getId()) > 0);
		return sharer;
	}

	/**
	 * Hands out one connection, if given one, then waits until closed.
	 */
	private static class FakeListener extends KeyAgreementListener {

		private final CountDownLatch closed = new CountDownLatch(1);
		private DuplexTransportConnection next;

		private FakeListener(DuplexTransportConnection next) {
			super(new BdfList());
			this.next = next;
		}

		@Override
		public KeyAgreementConnection accept() throws IOException {
			DuplexTransportConnection c = next;
			next = null;
			if (c != null) {
				return new KeyAgreementConnection(c, BluetoothConstants.ID);
			}
			try {
				closed.await();
			} catch (InterruptedException e) {
				throw new IOException(e);
			}
			throw new IOException("Closed");
		}

		@Override
		public void close() {
			closed.countDown();
		}
	}

	/**
	 * A connection that counts down when either side of it is disposed.
	 */
	private static class FakeConnection implements DuplexTransportConnection {

		private final CountDownLatch closed;

		private FakeConnection(CountDownLatch closed) {
			this.closed = closed;
		}

		@Override
		public TransportConnectionReader getReader() {
			return new TransportConnectionReader() {
				@Override
				public InputStream getInputStream() {
					return new ByteArrayInputStream(new byte[0]);
				}

				@Override
				public void dispose(boolean exception, boolean recognised) {
					closed.countDown();
				}
			};
		}

		@Override
		public TransportConnectionWriter getWriter() {
			return new TransportConnectionWriter() {
				@Override
				public long getMaxLatency() {
					return 0;
				}

				@Override
				public int getMaxIdleTime() {
					return 0;
				}

				@Override
				public boolean isLossyAndCheap() {
					return false;
				}

				@Override
				public OutputStream getOutputStream() {
					return new ByteArrayOutputStream();
				}

				@Override
				public void dispose(boolean exception) {
					closed.countDown();
				}
			};
		}

		@Override
		public TransportProperties getRemoteProperties() {
			return new TransportProperties();
		}
	}
}
