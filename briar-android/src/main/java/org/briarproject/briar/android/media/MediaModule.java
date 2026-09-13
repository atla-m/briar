package org.briarproject.briar.android.media;

import org.briarproject.briar.android.viewmodel.ViewModelKey;

import androidx.lifecycle.ViewModel;
import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public interface MediaModule {

	@Binds
	@IntoMap
	@ViewModelKey(MediaViewModel.class)
	ViewModel bindMediaViewModel(MediaViewModel mediaViewModel);

}
