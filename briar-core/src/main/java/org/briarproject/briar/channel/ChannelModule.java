package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelNearbyManager;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class ChannelModule {

	public static class EagerSingletons {
		@Inject
		ChannelManager channelManager;
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
			LifecycleManager lifecycleManager, EventBus eventBus,
			ContactManager contactManager, FeatureFlags featureFlags) {
		if (!featureFlags.shouldEnableBlogsInCore()) {
			return channelManager;
		}
		lifecycleManager.registerOpenDatabaseHook(channelManager);
		// Offers channels shared with contacts to new contacts
		contactManager.registerContactHook(channelManager);
		// Listens for Tor becoming active, then fetches on a timer
		eventBus.addListener(channelManager);
		return channelManager;
	}
}
