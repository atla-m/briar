package org.briarproject.bramble.test;

import org.briarproject.bramble.api.plugin.IsolatedSocketFactory;

import javax.net.SocketFactory;

import dagger.Module;
import dagger.Provides;

@Module
public class TestSocksModule {

	@Provides
	SocketFactory provideSocketFactory() {
		return SocketFactory.getDefault();
	}

	@Provides
	IsolatedSocketFactory provideIsolatedSocketFactory() {
		return new IsolatedSocketFactory() {
			@Override
			public SocketFactory create(String isolationKey) {
				return SocketFactory.getDefault();
			}

			@Override
			public SocketFactory create(String isolationKey,
					int extraConnectTimeout) {
				return SocketFactory.getDefault();
			}
		};
	}

}
