package org.briarproject.briar.android.media;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.IOException;
import java.util.Locale;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog.Builder;
import androidx.lifecycle.ViewModelProvider;

import static android.os.Build.VERSION.SDK_INT;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;
import static org.briarproject.briar.android.util.UiUtils.getDialogIcon;

/**
 * Plays an audio or video file shared in a private group or a private
 * conversation, and lets the user save it.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class MediaActivity extends BriarActivity {

	public static final String GROUP_ID = "groupId";
	public static final String MANIFEST_ID = "manifestId";
	public static final String NAME = "name";
	public static final String CONTENT_TYPE = "contentType";
	public static final String SIZE = "size";
	/**
	 * True if the file belongs to a private group, false if it belongs to
	 * a private conversation.
	 */
	public static final String IS_GROUP = "isGroup";

	private static final int PROGRESS_INTERVAL_MS = 250;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private MediaViewModel viewModel;
	private FileHeader header;
	private boolean isVideo;

	private SurfaceView videoSurface;
	private View audioView, loading;
	private ImageButton playPause;
	private SeekBar seekBar;
	private TextView timeView;

	@Nullable
	private MediaPlayer player = null;
	private boolean prepared = false, surfaceReady = false;
	@Nullable
	private MediaViewModel.Source pendingSource = null;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable updateProgress = this::updateProgress;

	private final ActivityResultLauncher<String> saveLauncher =
			registerForActivityResult(new CreateDocumentAdvanced(),
					this::onSaveUriChosen);

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(MediaViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_media);

		Intent i = getIntent();
		GroupId groupId = new GroupId(requireNonNull(
				i.getByteArrayExtra(GROUP_ID)));
		MessageId manifestId = new MessageId(requireNonNull(
				i.getByteArrayExtra(MANIFEST_ID)));
		String name = requireNonNull(i.getStringExtra(NAME));
		String contentType = requireNonNull(i.getStringExtra(CONTENT_TYPE));
		long size = i.getLongExtra(SIZE, 0);
		boolean isGroup = i.getBooleanExtra(IS_GROUP, true);
		header = new FileHeader(groupId, manifestId, name, contentType, size);
		isVideo = contentType.startsWith("video/");

		setUpCustomToolbar(false);
		setTitle(name);

		videoSurface = findViewById(R.id.videoSurface);
		audioView = findViewById(R.id.audioView);
		loading = findViewById(R.id.loading);
		playPause = findViewById(R.id.playPause);
		seekBar = findViewById(R.id.seekBar);
		timeView = findViewById(R.id.timeView);
		TextView audioName = findViewById(R.id.audioName);
		audioName.setText(name);

		if (isVideo) {
			videoSurface.setVisibility(VISIBLE);
			audioView.setVisibility(GONE);
			videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
				@Override
				public void surfaceCreated(SurfaceHolder holder) {
					surfaceReady = true;
					MediaPlayer p = player;
					if (p != null) p.setDisplay(holder);
					else if (pendingSource != null) startPlayer(pendingSource);
				}

				@Override
				public void surfaceChanged(SurfaceHolder holder, int format,
						int width, int height) {
				}

				@Override
				public void surfaceDestroyed(SurfaceHolder holder) {
					surfaceReady = false;
					MediaPlayer p = player;
					if (p != null) p.setDisplay(null);
				}
			});
		} else {
			videoSurface.setVisibility(GONE);
			audioView.setVisibility(VISIBLE);
		}

		playPause.setEnabled(false);
		playPause.setOnClickListener(v -> togglePlayback());
		seekBar.setEnabled(false);
		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int progress,
					boolean fromUser) {
				if (fromUser && player != null && prepared) {
					player.seekTo(progress);
					updateTime(progress, player.getDuration());
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar s) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar s) {
			}
		});

		viewModel.setFile(header, isGroup);
		viewModel.getSource().observe(this, source -> {
			if (isVideo && !surfaceReady) pendingSource = source;
			else startPlayer(source);
		});
		viewModel.getLoadError().observeEvent(this, error -> onPlaybackError());
		viewModel.getSaveError().observeEvent(this, error -> Toast.makeText(
				this, error ? R.string.save_file_error
						: R.string.save_file_success, LENGTH_SHORT).show());
	}

	private void startPlayer(MediaViewModel.Source source) {
		if (player != null) return;
		pendingSource = null;
		MediaPlayer p = new MediaPlayer();
		player = p;
		try {
			if (source.dataSource != null && SDK_INT >= 23) {
				p.setDataSource(source.dataSource);
			} else if (source.file != null) {
				p.setDataSource(source.file.getAbsolutePath());
			} else {
				throw new IOException("No source");
			}
			if (isVideo) p.setDisplay(videoSurface.getHolder());
			p.setOnPreparedListener(mp -> onPrepared());
			p.setOnCompletionListener(mp -> onCompletion());
			p.setOnErrorListener((mp, what, extra) -> {
				onPlaybackError();
				return true;
			});
			p.setOnVideoSizeChangedListener((mp, w, h) -> fitVideo(w, h));
			p.prepareAsync();
		} catch (IOException | IllegalArgumentException |
				IllegalStateException e) {
			onPlaybackError();
		}
	}

	private void onPrepared() {
		MediaPlayer p = player;
		if (p == null) return;
		prepared = true;
		loading.setVisibility(GONE);
		playPause.setEnabled(true);
		seekBar.setEnabled(true);
		seekBar.setMax(p.getDuration());
		updateTime(0, p.getDuration());
		p.start();
		playPause.setImageResource(R.drawable.ic_pause);
		handler.post(updateProgress);
	}

	private void onCompletion() {
		playPause.setImageResource(R.drawable.ic_play_arrow);
		handler.removeCallbacks(updateProgress);
		MediaPlayer p = player;
		if (p != null) {
			seekBar.setProgress(p.getDuration());
			updateTime(p.getDuration(), p.getDuration());
		}
	}

	private void onPlaybackError() {
		loading.setVisibility(GONE);
		Toast.makeText(this, R.string.media_error, LENGTH_LONG).show();
		releasePlayer();
	}

	private void togglePlayback() {
		MediaPlayer p = player;
		if (p == null || !prepared) return;
		if (p.isPlaying()) {
			p.pause();
			playPause.setImageResource(R.drawable.ic_play_arrow);
			handler.removeCallbacks(updateProgress);
		} else {
			p.start();
			playPause.setImageResource(R.drawable.ic_pause);
			handler.post(updateProgress);
		}
	}

	private void updateProgress() {
		MediaPlayer p = player;
		if (p == null || !prepared) return;
		int position = p.getCurrentPosition();
		seekBar.setProgress(position);
		updateTime(position, p.getDuration());
		if (p.isPlaying()) handler.postDelayed(updateProgress,
				PROGRESS_INTERVAL_MS);
	}

	private void updateTime(int position, int duration) {
		timeView.setText(String.format(Locale.getDefault(), "%s / %s",
				formatTime(position), formatTime(duration)));
	}

	private String formatTime(int millis) {
		int seconds = Math.max(0, millis / 1000);
		return String.format(Locale.getDefault(), "%d:%02d", seconds / 60,
				seconds % 60);
	}

	/**
	 * Sizes the video surface to the video's aspect ratio within the space
	 * available, so the picture isn't stretched.
	 */
	private void fitVideo(int videoWidth, int videoHeight) {
		if (videoWidth == 0 || videoHeight == 0) return;
		View container = findViewById(R.id.mediaContainer);
		int maxW = container.getWidth(), maxH = container.getHeight();
		if (maxW == 0 || maxH == 0) return;
		float videoAspect = (float) videoWidth / videoHeight;
		float containerAspect = (float) maxW / maxH;
		ViewGroup.LayoutParams lp = videoSurface.getLayoutParams();
		if (videoAspect > containerAspect) {
			lp.width = maxW;
			lp.height = (int) (maxW / videoAspect);
		} else {
			lp.height = maxH;
			lp.width = (int) (maxH * videoAspect);
		}
		videoSurface.setLayoutParams(lp);
	}

	private void releasePlayer() {
		handler.removeCallbacks(updateProgress);
		MediaPlayer p = player;
		player = null;
		prepared = false;
		if (p != null) {
			try {
				p.reset();
			} catch (IllegalStateException ignored) {
			}
			p.release();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		MediaPlayer p = player;
		if (p != null && prepared && p.isPlaying()) {
			p.pause();
			playPause.setImageResource(R.drawable.ic_play_arrow);
			handler.removeCallbacks(updateProgress);
		}
	}

	@Override
	protected void onDestroy() {
		releasePlayer();
		super.onDestroy();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.media_actions, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (item.getItemId() == R.id.action_save_file) {
			showSaveDialog();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void showSaveDialog() {
		Builder builder = new Builder(this, R.style.BriarDialogTheme);
		builder.setTitle(getString(R.string.dialog_title_save_file));
		builder.setMessage(getString(R.string.dialog_message_save_file));
		builder.setIcon(getDialogIcon(this, R.drawable.ic_security));
		builder.setPositiveButton(R.string.save_file, (dialog, which) -> {
			try {
				saveLauncher.launch(header.getName());
			} catch (ActivityNotFoundException e) {
				Toast.makeText(this, R.string.error_start_activity,
						LENGTH_LONG).show();
			}
		});
		builder.setNegativeButton(R.string.cancel, null);
		builder.show();
	}

	private void onSaveUriChosen(@Nullable Uri uri) {
		if (uri != null) viewModel.save(uri);
	}
}
