package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfReaderFactory;
import org.briarproject.bramble.api.data.BdfWriter;
import org.briarproject.bramble.api.data.BdfWriterFactory;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.attachment.CountingInputStream;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Logger;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_PUSHED_FILE_SIZE;
import static org.briarproject.briar.api.channel.ChannelConstants.MAX_IMPORT_BYTES;

/**
 * What two nearby phones say to each other about a channel, over a
 * connection keyed by the channel's public key. The reader asks, the
 * server sends its copy of the channel's file and then any attachment
 * file the reader asks for. Everything sent is in the published formats,
 * so the reader checks it exactly as it would a file from a mirror or a
 * hand-carried one: every post against the channel's key, and an image or
 * file only if a post carries it.
 * <p>
 * Each file is sent as {@code [length]} followed by that many bytes, so
 * the reader knows where it ends without the connection closing. The
 * whole channel file is sent every time: two copies of a channel need
 * not be byte-identical, so an offset into one means nothing to another.
 * Messages already held are skipped by ID when they are imported.
 */
@Immutable
@NotNullByDefault
public class NearbyChannelProtocol {

	static final int PROTOCOL_VERSION = 0;
	private static final long MANIFEST_WAIT_MS = 5_000;

	private static final Logger LOG =
			getLogger(NearbyChannelProtocol.class.getName());

	private final ChannelManager channelManager;
	private final BlogManager blogManager;
	private final BdfReaderFactory bdfReaderFactory;
	private final BdfWriterFactory bdfWriterFactory;

	@Inject
	public NearbyChannelProtocol(ChannelManager channelManager,
			BlogManager blogManager, BdfReaderFactory bdfReaderFactory,
			BdfWriterFactory bdfWriterFactory) {
		this.channelManager = channelManager;
		this.blogManager = blogManager;
		this.bdfReaderFactory = bdfReaderFactory;
		this.bdfWriterFactory = bdfWriterFactory;
	}

	/**
	 * Serves our copy of the channel to a reader, then every small
	 * attachment file we have complete, then says we are done.
	 */
	public void serve(GroupId g, InputStream in, OutputStream out)
			throws DbException, IOException, FormatException {
		BdfList hello = readList(in);
		if (hello.size() != 1 || hello.getInt(0) != PROTOCOL_VERSION)
			throw new FormatException();
		ByteArrayOutputStream file = new ByteArrayOutputStream();
		channelManager.exportChannel(g, file);
		sendFile(out, file.toByteArray());
		for (MessageId manifestId : channelManager.getCompleteFiles(g)) {
			FileHeader h = blogManager.getFileHeader(g, manifestId);
			if (h.getSize() > MAX_PUSHED_FILE_SIZE) continue;
			writeList(out, BdfList.of(manifestId));
			file = new ByteArrayOutputStream();
			channelManager.exportChannelFile(g, manifestId, file);
			sendFile(out, file.toByteArray());
		}
		writeList(out, new BdfList());
		out.flush();
	}

	/**
	 * Reads a nearby copy of the channel, then each small file it sends
	 * that we don't have complete.
	 *
	 * @return The number of messages read from the channel's file
	 */
	public int read(GroupId g, InputStream in, OutputStream out)
			throws DbException, IOException, FormatException {
		writeList(out, BdfList.of(PROTOCOL_VERSION));
		long length = readLength(in, MAX_IMPORT_BYTES);
		if (length == 0) throw new FormatException();
		int messages = channelManager.importChannel(
				new CountingInputStream(in, length));
		if (LOG.isLoggable(INFO)) {
			LOG.info("Read " + messages + " messages from a nearby copy");
		}
		while (true) {
			BdfList next = readList(in);
			if (next.isEmpty()) return messages;
			if (next.size() != 1) throw new FormatException();
			MessageId manifestId = new MessageId(next.getRaw(0));
			long fileLength = readLength(in, MAX_IMPORT_BYTES);
			CountingInputStream file = new CountingInputStream(in, fileLength);
			if (wantsFile(g, manifestId)) {
				channelManager.importChannelFile(g, manifestId, file);
			}
			// Whatever we didn't take, we still have to read past
			while (file.read() != -1) {
				// Discard
			}
		}
	}

	/**
	 * Returns true if we lack the given file and a post carries it. The
	 * manifest arrived in the channel's file a moment ago, so it may
	 * still be being validated; wait a little for it.
	 */
	private boolean wantsFile(GroupId g, MessageId manifestId)
			throws DbException {
		FileHeader h = null;
		long deadline = System.currentTimeMillis() + MANIFEST_WAIT_MS;
		while (h == null) {
			try {
				h = blogManager.getFileHeader(g, manifestId);
			} catch (NoSuchMessageException e) {
				if (System.currentTimeMillis() > deadline) return false;
				try {
					Thread.sleep(50);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
		}
		if (h.getSize() > MAX_PUSHED_FILE_SIZE) return false;
		return !blogManager.getFileStatus(h).isComplete();
	}

	private void sendFile(OutputStream out, byte[] file) throws IOException {
		writeList(out, BdfList.of((long) file.length));
		out.write(file);
		out.flush();
	}

	private long readLength(InputStream in, long max)
			throws IOException, FormatException {
		BdfList list = readList(in);
		if (list.size() != 1) throw new FormatException();
		long length = list.getLong(0);
		if (length < 0 || length > max) throw new FormatException();
		return length;
	}

	private BdfList readList(InputStream in)
			throws IOException, FormatException {
		// A fresh reader each time: it holds no bytes back between
		// lists, so the raw bytes that follow are read from the stream
		BdfReader r = bdfReaderFactory.createReader(in);
		return r.readList();
	}

	private void writeList(OutputStream out, BdfList list)
			throws IOException {
		BdfWriter w = bdfWriterFactory.createWriter(out);
		w.writeList(list);
		w.flush();
	}
}
