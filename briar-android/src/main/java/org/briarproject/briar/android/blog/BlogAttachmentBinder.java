package org.briarproject.briar.android.blog;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import org.briarproject.briar.R;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.attachment.FileRowBinder;
import org.briarproject.briar.android.media.MediaActivity;
import org.briarproject.briar.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;

import javax.annotation.Nullable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.UiThread;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.fragment.app.Fragment;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation;
import static org.briarproject.briar.android.conversation.ImageActivity.ATTACHMENTS;
import static org.briarproject.briar.android.conversation.ImageActivity.ATTACHMENT_POSITION;
import static org.briarproject.briar.android.conversation.ImageActivity.DATE;
import static org.briarproject.briar.android.conversation.ImageActivity.ITEM_ID;
import static org.briarproject.briar.android.conversation.ImageActivity.NAME;

/**
 * Opens the images and files carried by a blog or channel post: an image
 * full screen, an audio or video file in the player, anything else saved
 * wherever the user chooses. Shared by the screens that show posts, each of
 * which creates one as a field so its save launcher is registered in time.
 */
@UiThread
@NotNullByDefault
class BlogAttachmentBinder {

	private final Fragment fragment;
	private final ActivityResultLauncher<String> saveLauncher;

	// The file the user is choosing a location for
	@Nullable
	private FileHeader fileToSave = null;
	@Nullable
	private BaseViewModel viewModel = null;

	BlogAttachmentBinder(Fragment fragment) {
		this.fragment = fragment;
		saveLauncher = fragment.registerForActivityResult(
				new CreateDocumentAdvanced(), this::onSaveUriChosen);
	}

	/**
	 * Supplies the view model that saves files, which is not available
	 * when this binder is created.
	 */
	void setViewModel(BaseViewModel viewModel) {
		this.viewModel = viewModel;
	}

	void onAttachmentClicked(View view, BlogPostItem item,
			AttachmentItem attachment) {
		Context ctx = fragment.requireContext();
		ArrayList<AttachmentItem> attachments =
				new ArrayList<>(item.getAttachments());
		Intent i = new Intent(ctx,
				org.briarproject.briar.android.conversation.ImageActivity.class);
		i.putParcelableArrayListExtra(ATTACHMENTS, attachments);
		i.putExtra(ATTACHMENT_POSITION, attachments.indexOf(attachment));
		i.putExtra(NAME, item.getAuthor().getName());
		i.putExtra(DATE, item.getTimestamp());
		i.putExtra(ITEM_ID, item.getId().getBytes());
		String transitionName = attachment.getTransitionName(item.getId());
		ActivityOptionsCompat options = makeSceneTransitionAnimation(
				fragment.requireActivity(), view, transitionName);
		ActivityCompat.startActivity(ctx, i, options.toBundle());
	}

	void onFileClicked(BlogPostItem item, FileHeader header) {
		Context ctx = fragment.requireContext();
		FileStatus status = item.getFileStatus(header);
		if (status == null || !status.isComplete()) {
			Toast.makeText(ctx, R.string.file_still_receiving, LENGTH_SHORT)
					.show();
		} else if (FileRowBinder.isPlayable(header.getContentType())) {
			Intent i = new Intent(ctx, MediaActivity.class);
			i.putExtra(MediaActivity.GROUP_ID, header.getGroupId().getBytes());
			i.putExtra(MediaActivity.MANIFEST_ID,
					header.getManifestId().getBytes());
			i.putExtra(MediaActivity.NAME, header.getName());
			i.putExtra(MediaActivity.CONTENT_TYPE, header.getContentType());
			i.putExtra(MediaActivity.SIZE, header.getSize());
			i.putExtra(MediaActivity.CLIENT, MediaActivity.CLIENT_BLOG);
			ctx.startActivity(i);
		} else {
			// Nothing to show for other files; let the user save it
			fileToSave = header;
			try {
				saveLauncher.launch(header.getName());
			} catch (ActivityNotFoundException e) {
				Toast.makeText(ctx, R.string.error_start_activity, LENGTH_LONG)
						.show();
			}
		}
	}

	private void onSaveUriChosen(@Nullable Uri uri) {
		FileHeader header = fileToSave;
		fileToSave = null;
		BaseViewModel viewModel = this.viewModel;
		if (uri != null && header != null && viewModel != null) {
			viewModel.saveFile(header, uri);
		}
	}
}
