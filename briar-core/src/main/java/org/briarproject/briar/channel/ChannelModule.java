package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.sync.validation.ValidationManager;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelNearbyManager;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.briarproject.briar.channel.ChannelContactSharing.CLIENT_ID;
import static org.briarproject.briar.channel.ChannelContactSharing.MAJOR_VERSION;
import static org.briarproject.briar.channel.ChannelContactSharing.MINOR_VERSION;

@Module
public class ChannelModule {

	public static class EagerSingletons {
		@Inject
		ChannelManager channelManager;
		@Inject
		ChannelContactSharingValidator contactSharingValidator;
	}

	@Provides
	@Singleton
	ChannelContactSharingValidator provideContactSharingValidator(
			ValidationManager validationManager, ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock,
			FeatureFlags featureFlags) {
		ChannelContactSharingValidator validator =
				new ChannelContactSharingValidator(clientHelper,
						metadataEncoder, clock);
		if (featureFlags.shouldEnableBlogsInCore()) {
			validationManager.registerMessageValidator(CLIENT_ID,
					MAJOR_VERSION, validator);
		}
		return validator;
	}

	@Provides
	@Singleton
	ChannelNearbyManager provideChannelNearbyManager(
			NearbyChannelSharerImpl sharer) {
		return sharer;
	}

	@Provides
	@Singleton
	ChannelManager provideChannelManager(ChannelManagerImpl channelManager,
			ChannelContactSharing contactSharing,
			LifecycleManager lifecycleManager, EventBus eventBus,
			ContactManager contactManager,
			ValidationManager validationManager,
			ClientVersioningManager clientVersioningManager,
			FeatureFlags featureFlags) {
		if (!featureFlags.shouldEnableBlogsInCore()) {
			return channelManager;
		}
		lifecycleManager.registerOpenDatabaseHook(channelManager);
		// Tells each contact which channels we pass posts of, so a channel
		// is made visible only to contacts who pass it too
		lifecycleManager.registerOpenDatabaseHook(contactSharing);
		contactManager.registerContactHook(contactSharing);
		validationManager.registerIncomingMessageHook(CLIENT_ID,
				MAJOR_VERSION, contactSharing);
		clientVersioningManager.registerClient(CLIENT_ID, MAJOR_VERSION,
				MINOR_VERSION, contactSharing);
		// Listens for Tor becoming active, then fetches on a timer
		eventBus.addListener(channelManager);
		return channelManager;
	}
}
