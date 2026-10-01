package org.briarproject.briar.android.blog;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import com.google.zxing.Result;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.qrcode.CameraException;
import org.briarproject.briar.android.qrcode.CameraView;
import org.briarproject.briar.android.qrcode.QrCodeDecoder;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.core.content.ContextCompat;

import static android.Manifest.permission.CAMERA;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.widget.Toast.LENGTH_LONG;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.briar.api.channel.ChannelConstants.LINK_PREFIX;

/**
 * Reads a channel link from a QR code shown on another phone and returns
 * it as {@link #RESULT_LINK}, so a link can be passed in person without
 * typing or copying. The code is decoded on this phone; nothing is sent.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ScanChannelLinkActivity extends BriarActivity
		implements QrCodeDecoder.ResultCallback {

	public static final String RESULT_LINK = "briar.CHANNEL_LINK";

	private static final Logger LOG =
			getLogger(ScanChannelLinkActivity.class.getName());

	@Inject
	AndroidExecutor androidExecutor;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private CameraView cameraView;
	private QrCodeDecoder decoder;
	private final AtomicBoolean done = new AtomicBoolean(false);
	private final AtomicBoolean warned = new AtomicBoolean(false);
	private final ActivityResultLauncher<String> permissionLauncher =
			registerForActivityResult(new RequestPermission(), granted -> {
				if (granted) startCamera();
				else finishWithMessage(R.string.permission_camera_denied_body);
			});

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_scan_channel_link);
		setUpCustomToolbar(false);
		setTitle(R.string.channels_scan_qr);
		cameraView = findViewById(R.id.camera_view);
		decoder = new QrCodeDecoder(androidExecutor, ioExecutor, this);
		cameraView.setPreviewConsumer(decoder);
	}

	@Override
	public void onStart() {
		super.onStart();
		// Activity.checkSelfPermission() needs API 23; minSdk is 21
		if (ContextCompat.checkSelfPermission(this, CAMERA) ==
				PERMISSION_GRANTED) startCamera();
		else permissionLauncher.launch(CAMERA);
	}

	@Override
	public void onStop() {
		super.onStop();
		try {
			cameraView.stop();
		} catch (CameraException e) {
			logException(LOG, WARNING, e);
		}
	}

	private void startCamera() {
		try {
			cameraView.start();
		} catch (CameraException e) {
			logException(LOG, WARNING, e);
			finishWithMessage(R.string.camera_error);
		}
	}

	@Override
	public void onQrCodeDecoded(Result result) {
		// Called on the IO executor, possibly more than once
		String text = result.getText();
		if (!text.startsWith(LINK_PREFIX)) {
			// The decoder keeps decoding while the code is in view, so
			// say this once rather than once per frame
			if (warned.compareAndSet(false, true)) {
				runOnUiThread(() -> Toast.makeText(this,
						R.string.channels_scan_qr_not_link, LENGTH_LONG)
						.show());
			}
			return;
		}
		if (!done.compareAndSet(false, true)) return;
		runOnUiThread(() -> {
			Intent data = new Intent();
			data.putExtra(RESULT_LINK, text);
			setResult(RESULT_OK, data);
			finish();
		});
	}

	private void finishWithMessage(int res) {
		Toast.makeText(this, res, LENGTH_LONG).show();
		finish();
	}
}
