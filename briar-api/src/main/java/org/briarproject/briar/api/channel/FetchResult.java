package org.briarproject.briar.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * What happened when we tried to fetch a channel from its mirrors.
 * Reaching no mirror at all is told apart from a mirror saying the
 * channel hasn't changed, because to the reader those mean opposite
 * things: one is "you are up to date" and the other is "you may be
 * missing everything since you last fetched".
 */
@Immutable
@NotNullByDefault
public class FetchResult {

	public enum Outcome {
		/**
		 * The channel has no mirrors, so there was nowhere to fetch from.
		 */
		NO_MIRRORS,
		/**
		 * None of the channel's mirrors answered.
		 */
		UNREACHABLE,
		/**
		 * A mirror answered and said the channel hasn't changed since we
		 * last fetched it.
		 */
		UNCHANGED,
		/**
		 * A mirror served the channel and we read it.
		 */
		FETCHED,
		/**
		 * The channel's file has grown past what we will store from a
		 * mirror, so it can no longer be followed this way.
		 */
		TOO_LARGE,
		/**
		 * A fetch of the same channel or file was already under way, so
		 * nothing was done.
		 */
		IN_PROGRESS
	}

	private final Outcome outcome;
	private final int messages;

	public FetchResult(Outcome outcome, int messages) {
		this.outcome = outcome;
		this.messages = messages;
	}

	public Outcome getOutcome() {
		return outcome;
	}

	/**
	 * Returns how many messages were read from the mirror, which counts
	 * messages we already had. Zero unless the outcome is
	 * {@link Outcome#FETCHED}.
	 */
	public int getMessages() {
		return messages;
	}
}
