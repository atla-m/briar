package org.briarproject.briar.android.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Creates {@link AttachmentCreator}s that store attachments via a given
 * {@link AttachmentStore}. Each creator holds the state of one message
 * being composed, so callers should create one per screen.
 */
@NotNullByDefault
public interface AttachmentCreatorFactory {

	AttachmentCreator create(AttachmentStore store);

}
