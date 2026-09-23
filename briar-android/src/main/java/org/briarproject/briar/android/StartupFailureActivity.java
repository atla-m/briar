package org.briarproject.briar.android;

import android.content.Intent;
import android.os.Bundle;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BaseActivity;
import org.briarproject.briar.android.fragment.BaseFragment.BaseFragmentListener;
import org.briarproject.briar.android.fragment.ErrorFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static org.briarproject.bramble.api.lifecycle.LifecycleManager.StartResult;
import static org.briarproject.briar.android.BriarService.EXTRA_START_RESULT;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class StartupFailureActivity extends BaseActivity implements
		BaseFragmentListener {

	/**
	 * Below this much free space, a database that won't open is taken to
	 * be out of room rather than damaged.
	 */
	private static final long LOW_SPACE_BYTES = 64L * 1024 * 1024;

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		setContentView(R.layout.activity_fragment_container);
		handleIntent(getIntent());
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	private void handleIntent(Intent i) {
		StartResult result =
				(StartResult) i.getSerializableExtra(EXTRA_START_RESULT);

		// show proper error message
		int errorRes;
		switch (result) {
			case CLOCK_ERROR:
				errorRes = R.string.startup_failed_clock_error;
				break;
			case DATA_TOO_OLD_ERROR:
				errorRes = R.string.startup_failed_data_too_old_error;
				break;
			case DATA_TOO_NEW_ERROR:
				errorRes = R.string.startup_failed_data_too_new_error;
				break;
			case DB_ERROR:
				// On a phone that is out of space the usual advice, to
				// set up a new account, would delete everything to fix a
				// problem that freeing space fixes with nothing lost
				errorRes = isShortOfSpace()
						? R.string.startup_failed_storage_error
						: R.string.startup_failed_db_error;
				break;
			case SERVICE_ERROR:
				errorRes = R.string.startup_failed_service_error;
				break;
			default:
				throw new IllegalArgumentException();
		}
		showInitialFragment(ErrorFragment.newInstance(getString(errorRes)));
	}

	/**
	 * Returns true if the storage holding the account is nearly full, in
	 * which case that is almost certainly why the database couldn't be
	 * opened: it needs room to write as it opens.
	 */
	private boolean isShortOfSpace() {
		File dataDir = new File(getApplicationInfo().dataDir);
		return dataDir.getUsableSpace() < LOW_SPACE_BYTES;
	}

	@Override
	public void runOnDbThread(@NonNull Runnable runnable) {
		throw new UnsupportedOperationException();
	}
}
