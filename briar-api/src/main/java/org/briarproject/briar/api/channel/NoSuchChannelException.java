package org.briarproject.briar.api.channel;

import org.briarproject.bramble.api.db.DbException;

/**
 * Thrown when a channel we don't own is used as though we did.
 */
public class NoSuchChannelException extends DbException {
}
