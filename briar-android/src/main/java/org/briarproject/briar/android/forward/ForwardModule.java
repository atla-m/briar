package org.briarproject.briar.android.forward;

import org.briarproject.briar.android.activity.ActivityScope;

import dagger.Module;
import dagger.Provides;

@Module
public class ForwardModule {

	@ActivityScope
	@Provides
	ForwardPostController provideForwardPostController(
			ForwardPostControllerImpl forwardPostController) {
		return forwardPostController;
	}
}
