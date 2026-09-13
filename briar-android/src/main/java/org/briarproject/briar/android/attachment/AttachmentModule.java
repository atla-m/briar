package org.briarproject.briar.android.attachment;

import android.app.Application;

import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.android.attachment.media.ImageCompressor;
import org.briarproject.briar.api.messaging.MessagingManager;

import java.util.concurrent.Executor;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.briarproject.briar.android.attachment.AttachmentDimensions.getAttachmentDimensions;

@Module
public class AttachmentModule {

	@Provides
	AttachmentDimensions provideAttachmentDimensions(Application app) {
		return getAttachmentDimensions(app.getResources());
	}

	@Provides
	@Singleton
	AttachmentRetriever provideAttachmentRetriever(
			AttachmentRetrieverImpl attachmentRetriever) {
		return attachmentRetriever;
	}

	/**
	 * The attachment creator for private messages.
	 */
	@Provides
	@Singleton
	AttachmentCreator provideAttachmentCreator(Application app,
			@IoExecutor Executor ioExecutor, MessagingManager messagingManager,
			TransactionManager db, AttachmentRetriever retriever,
			ImageCompressor imageCompressor) {
		return new AttachmentCreatorImpl(app, ioExecutor,
				new MessagingAttachmentStore(messagingManager, db), retriever,
				imageCompressor);
	}

	@Provides
	AttachmentCreatorFactory provideAttachmentCreatorFactory(Application app,
			@IoExecutor Executor ioExecutor, AttachmentRetriever retriever,
			ImageCompressor imageCompressor) {
		return store -> new AttachmentCreatorImpl(app, ioExecutor, store,
				retriever, imageCompressor);
	}
}
