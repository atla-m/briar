package org.briarproject.briar.android.privategroup.conversation;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.attachment.FileRowBinder;
import org.briarproject.briar.android.conversation.ImageActivity;
import org.briarproject.briar.android.media.MediaActivity;
import org.briarproject.briar.android.privategroup.creation.GroupInviteActivity;
import org.briarproject.briar.android.privategroup.memberlist.GroupMemberListActivity;
import org.briarproject.briar.android.privategroup.reveal.RevealContactsActivity;
import org.briarproject.briar.android.threaded.ThreadListActivity;
import org.briarproject.briar.android.threaded.ThreadListViewModel;
import org.briarproject.briar.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import org.briarproject.briar.android.util.ActivityLaunchers.GetMultipleImagesAdvanced;
import org.briarproject.briar.android.util.ActivityLaunchers.OpenAnyDocumentAdvanced;
import org.briarproject.briar.android.util.ActivityLaunchers.OpenMultipleImageDocumentsAdvanced;
import org.briarproject.briar.android.view.ImagePreview;
import org.briarproject.briar.android.view.TextAttachmentController;
import org.briarproject.briar.android.view.TextAttachmentController.AttachmentListener;
import org.briarproject.briar.android.view.TextSendController;
import org.briarproject.briar.android.widget.LinkDialogFragment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation;
import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;
import static org.briarproject.briar.android.activity.RequestCodes.REQUEST_GROUP_INVITE;
import static org.briarproject.briar.android.conversation.ImageActivity.ATTACHMENTS;
import static org.briarproject.briar.android.conversation.ImageActivity.ATTACHMENT_POSITION;
import static org.briarproject.briar.android.conversation.ImageActivity.DATE;
import static org.briarproject.briar.android.conversation.ImageActivity.ITEM_ID;
import static org.briarproject.briar.android.conversation.ImageActivity.NAME;
import static org.briarproject.briar.android.util.UiUtils.launchActivityToOpenFile;
import static org.briarproject.briar.android.util.UiUtils.observeOnce;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_FILE_SIZE;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_POST_ATTACHMENTS;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_POST_TEXT_LENGTH;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class GroupActivity extends
		ThreadListActivity<GroupMessageItem, GroupMessageAdapter>
		implements AttachmentListener, GroupImageAdapter.Listener,
		GroupMessageAdapter.QuoteListener, GroupMessageAdapter.FileListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	@Inject
	FeatureFlags featureFlags;

	private final ActivityResultLauncher<String[]> docLauncher =
			registerForActivityResult(new OpenMultipleImageDocumentsAdvanced(),
					this::onImagesChosen);
	private final ActivityResultLauncher<String> contentLauncher =
			registerForActivityResult(new GetMultipleImagesAdvanced(),
					this::onImagesChosen);
	private final ActivityResultLauncher<String[]> fileLauncher =
			registerForActivityResult(new OpenAnyDocumentAdvanced(),
					this::onFileChosen);
	private final ActivityResultLauncher<String> saveLauncher =
			registerForActivityResult(new CreateDocumentAdvanced(),
					this::onSaveUriChosen);
	// The file the user is choosing a location for
	@Nullable
	private FileHeader fileToSave = null;

	private GroupViewModel viewModel;
	private boolean groupEnabled = false;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(GroupViewModel.class);
	}

	@Override
	protected ThreadListViewModel<GroupMessageItem> getViewModel() {
		return viewModel;
	}

	@Override
	protected GroupMessageAdapter createAdapter() {
		return new GroupMessageAdapter(this, this, this, this);
	}

	@Override
	public void onQuoteClick(MessageId parentId) {
		// Jump to the post being replied to
		scrollToItemAtTop(parentId);
	}

	@Override
	protected TextSendController createSendController() {
		if (!featureFlags.shouldEnableImageAttachments()) {
			return super.createSendController();
		}
		ImagePreview imagePreview = findViewById(R.id.imagePreview);
		TextAttachmentController controller = new TextAttachmentController(
				textInput, imagePreview, this, viewModel);
		// Attachments in groups were added in the same release as this UI,
		// so all members of a group we can post to support them
		controller.setImagesSupported();
		return controller;
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		Toolbar toolbar = setUpCustomToolbar(false);
		// Open member list on Toolbar click
		toolbar.setOnClickListener(v -> {
			Intent i = new Intent(GroupActivity.this,
					GroupMemberListActivity.class);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivity(i);
		});

		String groupName = getIntent().getStringExtra(GROUP_NAME);
		if (groupName != null) setTitle(groupName);
		observeOnce(viewModel.getPrivateGroup(), this, privateGroup ->
				setTitle(privateGroup.getName())
		);
		observeOnce(viewModel.isCreator(), this, adapter::setIsCreator);

		// start with group disabled and enable when not dissolved
		setGroupEnabled(false);
		viewModel.isDissolved().observe(this, dissolved -> {
			setGroupEnabled(!dissolved);
			// only show dialog when no prior state
			if (dissolved && state == null) onGroupDissolved();
		});

		// redraw a post when one of its attachments or files has changed
		viewModel.getAttachmentUpdated().observe(this, id -> {
			int position = adapter.findItemPosition(id);
			if (position != NO_POSITION) adapter.notifyItemChanged(position);
		});
		viewModel.getFileError().observeEvent(this, res -> {
			String msg = res == R.string.file_too_big
					? getString(res, MAX_FILE_SIZE / 1024 / 1024)
					: getString(res);
			Toast.makeText(this, msg, LENGTH_LONG).show();
		});
		viewModel.getSaveError().observeEvent(this, error -> Toast.makeText(
				this, error ? R.string.save_file_error
						: R.string.save_file_success, LENGTH_SHORT).show());
	}

	// Files of any type, sent as chunks

	private void onFileChosen(@Nullable Uri uri) {
		if (uri != null) viewModel.sendFile(uri);
	}

	@Override
	public void onFileClick(GroupMessageItem item, FileHeader header) {
		FileStatus status = item.getFileStatus(header);
		if (status == null || !status.isComplete()) {
			Toast.makeText(this, R.string.file_still_receiving, LENGTH_SHORT)
					.show();
		} else if (FileRowBinder.isPlayable(header.getContentType())) {
			Intent i = new Intent(this, MediaActivity.class);
			i.putExtra(MediaActivity.GROUP_ID, header.getGroupId().getBytes());
			i.putExtra(MediaActivity.MANIFEST_ID,
					header.getManifestId().getBytes());
			i.putExtra(MediaActivity.NAME, header.getName());
			i.putExtra(MediaActivity.CONTENT_TYPE, header.getContentType());
			i.putExtra(MediaActivity.SIZE, header.getSize());
			i.putExtra(MediaActivity.IS_GROUP, true);
			startActivity(i);
		} else {
			// Nothing to show for other files; let the user save it
			fileToSave = header;
			try {
				saveLauncher.launch(header.getName());
			} catch (ActivityNotFoundException e) {
				Toast.makeText(this, R.string.error_start_activity,
						LENGTH_LONG).show();
			}
		}
	}

	private void onSaveUriChosen(@Nullable Uri uri) {
		FileHeader header = fileToSave;
		fileToSave = null;
		if (uri != null && header != null) viewModel.saveFile(header, uri);
	}

	@Override
	protected void createAndStoreMessage(@Nullable String text,
			List<AttachmentHeader> headers, @Nullable MessageId replyId) {
		viewModel.createAndStoreMessage(text, headers, replyId);
	}

	@Override
	public void onAttachImageClicked() {
		launchActivityToOpenFile(this, docLauncher, contentLauncher, "image/*");
	}

	private void onImagesChosen(@Nullable List<Uri> uris) {
		if (sendController instanceof TextAttachmentController) {
			((TextAttachmentController) sendController).onImageReceived(uris);
		}
	}

	@Override
	public void onTooManyAttachments() {
		String format = getResources().getString(
				R.string.messaging_too_many_attachments_toast);
		String warning = String.format(format, MAX_GROUP_POST_ATTACHMENTS);
		Toast.makeText(this, warning, LENGTH_SHORT).show();
	}

	@Override
	public void onAttachmentClicked(View view, GroupMessageItem item,
			AttachmentItem attachment) {
		ArrayList<AttachmentItem> attachments =
				new ArrayList<>(item.getAttachments());
		Intent i = new Intent(this, ImageActivity.class);
		i.putParcelableArrayListExtra(ATTACHMENTS, attachments);
		i.putExtra(ATTACHMENT_POSITION, attachments.indexOf(attachment));
		i.putExtra(NAME, item.getAuthorName());
		i.putExtra(DATE, item.getTimestamp());
		i.putExtra(ITEM_ID, item.getId().getBytes());
		String transitionName = attachment.getTransitionName(item.getId());
		ActivityOptionsCompat options =
				makeSceneTransitionAnimation(this, view, transitionName);
		ActivityCompat.startActivity(this, i, options.toBundle());
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu items for use in the action bar
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.group_actions, menu);

		// show items based on role (which will not change, so observe once)
		observeOnce(viewModel.isCreator(), this, isCreator -> {
			menu.findItem(R.id.action_group_reveal).setVisible(!isCreator);
			menu.findItem(R.id.action_group_invite).setVisible(isCreator);
			menu.findItem(R.id.action_group_leave).setVisible(!isCreator);
			menu.findItem(R.id.action_group_dissolve).setVisible(isCreator);
		});
		super.onCreateOptionsMenu(menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		// Files can't be sent once the group has been dissolved
		menu.findItem(R.id.action_group_send_file)
				.setVisible(groupEnabled);
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_group_send_file) {
			try {
				fileLauncher.launch(new String[] {"*/*"});
			} catch (ActivityNotFoundException e) {
				Toast.makeText(this, R.string.error_start_activity,
						LENGTH_LONG).show();
			}
			return true;
		} else if (itemId == R.id.action_group_member_list) {
			Intent i = new Intent(this, GroupMemberListActivity.class);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivity(i);
			return true;
		} else if (itemId == R.id.action_group_reveal) {
			if (requireNonNull(viewModel.isCreator().getValue()))
				throw new IllegalStateException();
			Intent i = new Intent(this, RevealContactsActivity.class);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivity(i);
			return true;
		} else if (itemId == R.id.action_group_invite) {
			if (!requireNonNull(viewModel.isCreator().getValue()))
				throw new IllegalStateException();
			Intent i = new Intent(this, GroupInviteActivity.class);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivityForResult(i, REQUEST_GROUP_INVITE);
			return true;
		} else if (itemId == R.id.action_group_leave) {
			if (requireNonNull(viewModel.isCreator().getValue()))
				throw new IllegalStateException();
			showLeaveGroupDialog();
			return true;
		} else if (itemId == R.id.action_group_dissolve) {
			if (!requireNonNull(viewModel.isCreator().getValue()))
				throw new IllegalStateException();
			showDissolveGroupDialog();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onActivityResult(int request, int result,
			@Nullable Intent data) {
		if (request == REQUEST_GROUP_INVITE && result == RESULT_OK) {
			displaySnackbar(R.string.groups_invitation_sent);
		} else super.onActivityResult(request, result, data);
	}

	@Override
	protected int getMaxTextLength() {
		return MAX_GROUP_POST_TEXT_LENGTH;
	}

	@Override
	public void onReplyClick(GroupMessageItem item) {
		Boolean isDissolved = viewModel.isDissolved().getValue();
		if (isDissolved != null && !isDissolved) super.onReplyClick(item);
	}

	@Override
	public void onLinkClick(String url){
		LinkDialogFragment f = LinkDialogFragment.newInstance(url);
		f.show(getSupportFragmentManager(), f.getUniqueTag());
	}

	private void setGroupEnabled(boolean enabled) {
		groupEnabled = enabled;
		invalidateOptionsMenu();
		sendController.setReady(enabled);
		list.getRecyclerView().setAlpha(enabled ? 1f : 0.5f);

		if (!enabled) {
			textInput.setVisibility(GONE);
			if (textInput.isKeyboardOpen()) textInput.hideSoftKeyboard();
		} else {
			textInput.setVisibility(VISIBLE);
		}
	}

	private void showLeaveGroupDialog() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				this, R.style.BriarDialogTheme);
		builder.setTitle(getString(R.string.groups_leave_dialog_title));
		builder.setMessage(getString(R.string.groups_leave_dialog_message));
		builder.setNegativeButton(R.string.dialog_button_leave,
				(d, w) -> deleteGroup());
		builder.setPositiveButton(R.string.cancel, null);
		builder.show();
	}

	private void showDissolveGroupDialog() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				this, R.style.BriarDialogTheme);
		builder.setTitle(getString(R.string.groups_dissolve_dialog_title));
		builder.setMessage(getString(R.string.groups_dissolve_dialog_message));
		builder.setNegativeButton(R.string.groups_dissolve_button,
				(d, w) -> deleteGroup());
		builder.setPositiveButton(R.string.cancel, null);
		builder.show();
	}

	private void deleteGroup() {
		// The activity is going to be destroyed by the
		// GroupRemovedEvent being fired
		viewModel.deletePrivateGroup();
	}

	private void onGroupDissolved() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				this, R.style.BriarDialogTheme);
		builder.setTitle(getString(R.string.groups_dissolved_dialog_title));
		builder.setMessage(getString(R.string.groups_dissolved_dialog_message));
		builder.setNeutralButton(R.string.ok, null);
		builder.show();
	}

}
