package org.briarproject.briar.channel;

import org.briarproject.bramble.api.Cancellable;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.keyagreement.KeyAgreementConnection;
import org.briarproject.bramble.api.keyagreement.KeyAgreementListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.plugin.BluetoothConstants;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.sync.GroupId;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

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
 * Shares a channel with anyone nearby who holds its link, over Bluetooth.
 * The connection is the one used to add a contact in person, keyed not
 * by a secret from a QR code but by a value anyone with the link can
 * compute from the channel's public key: whoever connects has the link,
 * and that is all the connection proves or needs to. While sharing is
 * on, the phone listens for such connections and serves its copy, and
 * scans for nearby phones that listen, and reads theirs.
 */
@ThreadSafe
@NotNullByDefault
class NearbyChannelSharerImpl implements ChannelNearbyManager {

	private static final Logger LOG =
			getLogger(NearbyChannelSharerImpl.class.getName());

	private static final String LABEL_COMMITMENT =
			"org.briarproject.briar.channel/NEARBY";
	private static final long FIRST_SCAN_DELAY_MS = 5_000;
	private static final long SCAN_INTERVAL_MS = 2 * 60 * 1000;

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
	 * can compute it: it is not a secret, and proves only that whoever
	 * connects holds the link.
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
				DuplexTransportConnection conn = c.getConnection();
				boolean error = false;
				try {
					InputStream in = conn.getReader().getInputStream();
					OutputStream out = conn.getWriter().getOutputStream();
					protocol.serve(g, in, out);
				} catch (DbException | IOException e) {
					logException(LOG, INFO, e);
					error = true;
				} finally {
					dispose(conn, error);
				}
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
			boolean error = false;
			try {
				InputStream in = conn.getReader().getInputStream();
				OutputStream out = conn.getWriter().getOutputStream();
				protocol.read(g, in, out);
			} catch (DbException | IOException e) {
				logException(LOG, INFO, e);
				error = true;
			} finally {
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
