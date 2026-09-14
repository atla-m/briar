package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.briar.api.channel.ChannelManager;

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
	ChannelManager provideChannelManager(ChannelManagerImpl channelManager,
			LifecycleManager lifecycleManager, EventBus eventBus,
			FeatureFlags featureFlags) {
		if (!featureFlags.shouldEnableBlogsInCore()) {
			return channelManager;
		}
		lifecycleManager.registerOpenDatabaseHook(channelManager);
		// Listens for Tor becoming active, then fetches on a timer
		eventBus.addListener(channelManager);
		return channelManager;
	}
}
