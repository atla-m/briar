package org.briarproject.briar.android.blog;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.attachment.FileRowBinder;
import org.briarproject.briar.android.util.ActivityLaunchers.GetMultipleImagesAdvanced;
import org.briarproject.briar.android.util.ActivityLaunchers.OpenAnyDocumentAdvanced;
import org.briarproject.briar.android.util.ActivityLaunchers.OpenMultipleImageDocumentsAdvanced;
import org.briarproject.briar.android.view.ImagePreview;
import org.briarproject.briar.android.view.TextAttachmentController;
import org.briarproject.briar.android.view.TextAttachmentController.AttachmentListener;
import org.briarproject.briar.android.view.TextInputView;
import org.briarproject.briar.api.android.AndroidNotificationManager;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.List;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import static android.text.util.Linkify.WEB_URLS;
import static android.text.util.Linkify.addLinks;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static androidx.core.text.HtmlCompat.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL;
import static androidx.core.text.HtmlCompat.toHtml;
import static com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_SHORT;
import static java.util.Collections.emptyList;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static org.briarproject.bramble.util.StringUtils.toUtf8;
import static org.briarproject.briar.android.util.UiUtils.launchActivityToOpenFile;
import static org.briarproject.briar.android.view.TextSendController.SendState;
import static org.briarproject.briar.android.view.TextSendController.SendState.SENT;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;
import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_POST_ATTACHMENTS;
import static org.briarproject.briar.api.blog.BlogConstants.MAX_BLOG_POST_TEXT_LENGTH;
import static org.briarproject.briar.util.HtmlUtils.cleanArticle;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class WriteBlogPostActivity extends BriarActivity
		implements AttachmentListener {

	@Inject
	AndroidNotificationManager notificationManager;
	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private WriteBlogPostViewModel viewModel;
	private TextInputView input;
	private TextAttachmentController sendController;
	private LinearLayout fileList;
	private ProgressBar progressBar;
	private GroupId groupId;

	private final ActivityResultLauncher<String[]> docLauncher =
			registerForActivityResult(new OpenMultipleImageDocumentsAdvanced(),
					this::onImagesChosen);
	private final ActivityResultLauncher<String> contentLauncher =
			registerForActivityResult(new GetMultipleImagesAdvanced(),
					this::onImagesChosen);
	private final ActivityResultLauncher<String[]> fileLauncher =
			registerForActivityResult(new OpenAnyDocumentAdvanced(),
					this::onFileChosen);

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(WriteBlogPostViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		Intent i = getIntent();
		byte[] b = i.getByteArrayExtra(GROUP_ID);
		if (b == null) throw new IllegalStateException("No Group in intent.");
		groupId = new GroupId(b);
		viewModel.setGroupId(groupId);

		setContentView(R.layout.activity_write_blog_post);

		input = findViewById(R.id.textInput);
		ImagePreview imagePreview = findViewById(R.id.imagePreview);
		sendController = new TextAttachmentController(input, imagePreview,
				this, viewModel);
		// Posts carrying images are a new message format, so every client
		// that can read this post at all can read its images
		sendController.setImagesSupported();
		input.setSendController(sendController);
		input.setMaxTextLength(MAX_BLOG_POST_TEXT_LENGTH);
		input.setReady(true);

		fileList = findViewById(R.id.fileList);
		progressBar = findViewById(R.id.progressBar);

		viewModel.getAttachedFiles().observe(this, headers -> {
			FileRowBinder.bindAttached(fileList, headers);
			sendController.setHasFiles(!headers.isEmpty());
		});
		viewModel.getFileError().observeEvent(this, res -> {
			String msg = res == R.string.file_too_big
					? getString(res, MAX_FILE_SIZE / 1024 / 1024)
					: getString(res);
			Toast.makeText(this, msg, LENGTH_LONG).show();
		});
		viewModel.getPublished().observeEvent(this, published -> {
			if (published) {
				setResult(RESULT_OK);
				supportFinishAfterTransition();
			} else {
				// hide progress bar, show the input again
				progressBar.setVisibility(GONE);
				input.setVisibility(VISIBLE);
				Toast.makeText(this, R.string.blogs_publishing_blog_post_error,
						LENGTH_LONG).show();
			}
		});
	}

	@Override
	public void onStart() {
		super.onStart();
		notificationManager.blockNotification(groupId);
	}

	@Override
	public void onStop() {
		super.onStop();
		notificationManager.unblockNotification(groupId);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.write_blog_post_actions, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (id == R.id.action_attach_image) {
			sendController.onImageButtonClicked();
			return true;
		} else if (id == R.id.action_attach_file) {
			try {
				fileLauncher.launch(new String[] {"*/*"});
			} catch (RuntimeException e) {
				Toast.makeText(this, R.string.error_start_activity,
						LENGTH_LONG).show();
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onAttachImageClicked() {
		launchActivityToOpenFile(this, docLauncher, contentLauncher, "image/*");
	}

	private void onImagesChosen(@Nullable List<Uri> uris) {
		sendController.onImageReceived(uris);
	}

	private void onFileChosen(@Nullable Uri uri) {
		if (uri != null) viewModel.attachFile(uri);
	}

	@Override
	public void onTooManyAttachments() {
		String format = getString(R.string.messaging_too_many_attachments_toast);
		String warning = String.format(format, MAX_BLOG_POST_ATTACHMENTS);
		Toast.makeText(this, warning, LENGTH_SHORT).show();
	}

	@Override
	public LiveData<SendState> onSendClick(@Nullable String text,
			List<AttachmentHeader> headers, long expectedAutoDeleteTimer) {
		boolean hasAttachments =
				!headers.isEmpty() || !getAttachedFiles().isEmpty();
		if (isNullOrEmpty(text) && !hasAttachments) throw new AssertionError();

		String html = null;
		if (!isNullOrEmpty(text)) {
			SpannableStringBuilder ssb = SpannableStringBuilder.valueOf(text);
			addLinks(ssb, WEB_URLS);
			html = cleanArticle(toHtml(ssb,
					TO_HTML_PARAGRAPH_LINES_INDIVIDUAL));
			if (toUtf8(html).length > MAX_BLOG_POST_TEXT_LENGTH) {
				Snackbar.make(input, R.string.text_too_long, LENGTH_SHORT)
						.show();
				return new MutableLiveData<>(null);
			}
		}

		// hide publish button, show progress bar
		input.hideSoftKeyboard();
		input.setVisibility(GONE);
		progressBar.setVisibility(VISIBLE);

		viewModel.publish(html, headers);
		return new MutableLiveData<>(SENT);
	}

	private List<FileHeader> getAttachedFiles() {
		List<FileHeader> files = viewModel.getAttachedFiles().getValue();
		return files == null ? emptyList() : files;
	}
}
