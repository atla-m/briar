package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FeatureFlags;
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
			LifecycleManager lifecycleManager, FeatureFlags featureFlags) {
		if (!featureFlags.shouldEnableBlogsInCore()) {
			return channelManager;
		}
		lifecycleManager.registerOpenDatabaseHook(channelManager);
		return channelManager;
	}
}
