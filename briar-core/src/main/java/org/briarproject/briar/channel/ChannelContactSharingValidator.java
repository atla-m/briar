package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.BdfMessageContext;
import org.briarproject.bramble.api.client.BdfMessageValidator;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.system.Clock;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.channel.ChannelContactSharing.MAX_TOKENS;
import static org.briarproject.briar.channel.ChannelContactSharing.MSG_KEY_LOCAL;
import static org.briarproject.briar.channel.ChannelContactSharing.MSG_KEY_VERSION;

/**
 * Checks a contact's list of the channels they pass posts of: a version
 * that orders their lists, then up to {@link
 * ChannelContactSharing#MAX_TOKENS} tokens of 32 bytes.
 */
@Immutable
@NotNullByDefault
class ChannelContactSharingValidator extends BdfMessageValidator {

	private static final int TOKEN_LENGTH = 32;

	ChannelContactSharingValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock) {
		super(clientHelper, metadataEncoder, clock);
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws FormatException {
		checkSize(body, 2);
		long version = body.getLong(0);
		if (version < 1) throw new FormatException();
		BdfList tokens = body.getList(1);
		checkSize(tokens, 0, MAX_TOKENS);
		for (int i = 0; i < tokens.size(); i++) {
			checkLength(tokens.getRaw(i), TOKEN_LENGTH);
		}
		return new BdfMessageContext(BdfDictionary.of(
				new BdfEntry(MSG_KEY_LOCAL, false),
				new BdfEntry(MSG_KEY_VERSION, version)));
	}
}
