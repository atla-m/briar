package org.briarproject.briar.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * A stream that reads no more than it has been allowed since the last
 * allowance, and then appears to end. A channel stream comes from anyone,
 * and the BDF reader builds a list in memory however many elements it
 * has, so each part of the stream is given an allowance before it is
 * read: a list that runs past its allowance is malformed rather than a
 * way to run the phone out of memory.
 */
@NotThreadSafe
@NotNullByDefault
class AllowanceInputStream extends InputStream {

	private final InputStream delegate;
	private long remaining = 0;

	AllowanceInputStream(InputStream delegate) {
		this.delegate = delegate;
	}

	/**
	 * Allows the given number of bytes to be read from now on, replacing
	 * what was left of any earlier allowance.
	 */
	void allow(long bytes) {
		remaining = bytes;
	}

	@Override
	public int read() throws IOException {
		if (remaining <= 0) return -1;
		int i = delegate.read();
		if (i != -1) remaining--;
		return i;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (len == 0) return 0;
		if (remaining <= 0) return -1;
		if (len > remaining) len = (int) remaining;
		int read = delegate.read(b, off, len);
		if (read > 0) remaining -= read;
		return read;
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}
}
