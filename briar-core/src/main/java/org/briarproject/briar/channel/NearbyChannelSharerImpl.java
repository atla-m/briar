package org.briarproject.briar.channel;

import org.briarproject.bramble.api.Cancellable;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.keyagreement.KeyAgreementConnection;
import org.briarproject.bramble.api.keyagreement.KeyAgreementListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.plugin.BluetoothConstants;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.channel.ChannelNearbyManager;
import org.briarproject.briar.api.channel.event.ChannelNearbyEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.Collections.newSetFromMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.keyagreement.KeyAgreementConstants.COMMIT_LENGTH;
import static org.briarproject.bramble.api.keyagreement.KeyAgreementConstants.TRANSPORT_ID_BLUETOOTH;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.bramble.util.LogUtils.logException;

/**
 * Shares a channel with anyone nearby, over Bluetooth. The connection is
 * the one used to add a contact in person, keyed not by a secret from a
 * QR code but by a value computed from the channel's public key. That
 * value is not a secret and proves nothing: the phone lists it among its
 * Bluetooth services, where anyone in range can read it without the
 * link, and whoever connects is served. While sharing is on, the phone
 * listens for such connections and serves its copy, and scans for nearby
 * phones that listen, and reads theirs.
 */
@ThreadSafe
@NotNullByDefault
class NearbyChannelSharerImpl implements ChannelNearbyManager, EventListener {

	private static final Logger LOG =
			getLogger(NearbyChannelSharerImpl.class.getName());

	private static final String LABEL_COMMITMENT =
			"org.briarproject.briar.channel/NEARBY";
	private static final long FIRST_SCAN_DELAY_MS = 5_000;
	private static final long SCAN_INTERVAL_MS = 2 * 60 * 1000;
	/**
	 * How long one exchange with a nearby phone may take before it is
	 * cut off, so a stranger who connects and goes quiet can't hold the
	 * connection, and the wake lock it keeps, for the rest of the hour.
	 */
	private static final long EXCHANGE_TIMEOUT_MS = 10 * 60 * 1000;

	private final DatabaseComponent db;
	private final BlogManager blogManager;
	private final NearbyChannelProtocol protocol;
	private final PluginManager pluginManager;
	private final CryptoComponent crypto;
	private final TaskScheduler scheduler;
	private final Executor ioExecutor;
	private final EventBus eventBus;
	private final Clock clock;

	private final Map<GroupId, Session> sessions = new ConcurrentHashMap<>();

	@Inject
	NearbyChannelSharerImpl(DatabaseComponent db, BlogManager blogManager,
			NearbyChannelProtocol protocol, PluginManager pluginManager,
			CryptoComponent crypto, TaskScheduler scheduler,
			@IoExecutor Executor ioExecutor, EventBus eventBus, Clock clock) {
		this.db = db;
		this.blogManager = blogManager;
		this.protocol = protocol;
		this.pluginManager = pluginManager;
		this.crypto = crypto;
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		this.eventBus = eventBus;
		this.clock = clock;
	}

	@Override
	public boolean setSharingNearby(GroupId g, boolean on) {
		if (!on) {
			Session s = sessions.remove(g);
			if (s != null) s.close();
			eventBus.broadcast(new ChannelNearbyEvent(g, 0));
			return true;
		}
		if (sessions.containsKey(g)) return true;
		DuplexPlugin plugin = getBluetoothPlugin();
		if (plugin == null) return false;
		byte[] commitment;
		try {
			commitment = getCommitment(g);
		} catch (DbException e) {
			logException(LOG, WARNING, e);
			return false;
		}
		KeyAgreementListener listener =
				plugin.createKeyAgreementListener(commitment);
		if (listener == null) return false;
		long expiry = clock.currentTimeMillis() + NEARBY_DURATION_MS;
		Session s = new Session(g, plugin, commitment, listener, expiry);
		sessions.put(g, s);
		ioExecutor.execute(s::acceptLoop);
		s.scan = scheduler.scheduleWithFixedDelay(s::scan, ioExecutor,
				FIRST_SCAN_DELAY_MS, SCAN_INTERVAL_MS, MILLISECONDS);
		s.timer = scheduler.schedule(() -> expire(g, s), ioExecutor,
				NEARBY_DURATION_MS, MILLISECONDS);
		eventBus.broadcast(new ChannelNearbyEvent(g, expiry));
		return true;
	}

	/**
	 * Stops sharing a channel nearby when it is deleted or unsubscribed
	 * from, so the phone stops advertising a channel it no longer holds.
	 */
	@Override
	public void eventOccurred(Event e) {
		if (e instanceof GroupRemovedEvent) {
			GroupId g = ((GroupRemovedEvent) e).getGroup().getId();
			if (sessions.containsKey(g)) setSharingNearby(g, false);
		}
	}

	@Override
	public long getNearbyExpiry(GroupId g) {
		Session s = sessions.get(g);
		return s == null ? 0 : s.expiry;
	}

	private void expire(GroupId g, Session s) {
		if (sessions.remove(g, s)) {
			s.close();
			eventBus.broadcast(new ChannelNearbyEvent(g, 0));
		}
	}

	@Nullable
	private DuplexPlugin getBluetoothPlugin() {
		Plugin p = pluginManager.getPlugin(BluetoothConstants.ID);
		if (!(p instanceof DuplexPlugin) || p.getState() != ACTIVE) {
			LOG.info("Bluetooth is not available");
			return null;
		}
		return (DuplexPlugin) p;
	}

	/**
	 * Returns the value the connection is keyed by. Anyone with the link
	 * can compute it, and anyone in range can read it from the phone's
	 * list of Bluetooth services, so it proves nothing about whoever
	 * connects.
	 */
	private byte[] getCommitment(GroupId g) throws DbException {
		Blog blog = db.transactionWithResult(true,
				txn -> blogManager.getBlog(txn, g));
		byte[] hash = crypto.hash(LABEL_COMMITMENT,
				blog.getAuthor().getPublicKey().getEncoded());
		return Arrays.copyOf(hash, COMMIT_LENGTH);
	}

	private class Session {

		private final GroupId g;
		private final DuplexPlugin plugin;
		private final byte[] commitment;
		private final KeyAgreementListener listener;
		private final long expiry;
		private volatile boolean closed = false;
		@Nullable
		private volatile Cancellable scan = null, timer = null;
		private final Set<DuplexTransportConnection> open =
				newSetFromMap(new ConcurrentHashMap<>());

		private Session(GroupId g, DuplexPlugin plugin, byte[] commitment,
				KeyAgreementListener listener, long expiry) {
			this.g = g;
			this.plugin = plugin;
			this.commitment = commitment;
			this.listener = listener;
			this.expiry = expiry;
		}

		private void close() {
			closed = true;
			Cancellable c = scan;
			if (c != null) c.cancel();
			c = timer;
			if (c != null) c.cancel();
			listener.close();
			// Closing the listener doesn't end an exchange in progress
			for (DuplexTransportConnection conn : open) dispose(conn, true);
		}

		/**
		 * Serves one reader at a time, until sharing is turned off.
		 */
		private void acceptLoop() {
			while (!closed) {
				KeyAgreementConnection c;
				try {
					c = listener.accept();
				} catch (IOException e) {
					if (!closed) logException(LOG, INFO, e);
					return;
				}
				LOG.info("Serving a nearby reader");
				exchange(c.getConnection(), true);
			}
		}

		/**
		 * Looks for a nearby phone serving the channel and reads its copy.
		 */
		private void scan() {
			if (closed) return;
			LOG.info("Looking for a nearby copy");
			// No address in the descriptor: discover nearby devices and
			// try each for the channel's service
			DuplexTransportConnection conn = plugin.createKeyAgreementConnection(
					commitment, BdfList.of(TRANSPORT_ID_BLUETOOTH));
			if (conn == null || closed) {
				if (conn != null) dispose(conn, false);
				return;
			}
			exchange(conn, false);
		}

		/**
		 * Serves our copy over a connection, or reads theirs, within the
		 * time an exchange may take. The connection is closed when the
		 * exchange ends, when the time is up, or when the session is.
		 */
		private void exchange(DuplexTransportConnection conn,
				boolean serving) {
			open.add(conn);
			Cancellable deadline = scheduler.schedule(() -> {
				LOG.info("Nearby exchange timed out");
				dispose(conn, true);
			}, ioExecutor, EXCHANGE_TIMEOUT_MS, MILLISECONDS);
			boolean error = false;
			try {
				// The session may have closed while we were connecting
				if (closed) return;
				InputStream in = conn.getReader().getInputStream();
				OutputStream out = conn.getWriter().getOutputStream();
				if (serving) protocol.serve(g, in, out);
				else protocol.read(g, in, out);
			} catch (DbException | IOException e) {
				logException(LOG, INFO, e);
				error = true;
			} finally {
				deadline.cancel();
				open.remove(conn);
				dispose(conn, error);
			}
		}

		private void dispose(DuplexTransportConnection conn, boolean error) {
			try {
				conn.getReader().dispose(error, true);
			} catch (IOException e) {
				logException(LOG, INFO, e);
			}
			try {
				conn.getWriter().dispose(error);
			} catch (IOException e) {
				logException(LOG, INFO, e);
			}
		}
	}
}
