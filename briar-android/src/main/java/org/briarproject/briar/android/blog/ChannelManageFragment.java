package org.briarproject.briar.android.blog;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.blog.ChannelViewModel.PendingImport;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.blog.ChannelAdapter.ChannelListener;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import org.briarproject.briar.android.util.ActivityLaunchers.OpenAnyDocumentAdvanced;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.api.channel.FetchResult;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.widget.Toast.LENGTH_LONG;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelManageFragment extends BaseFragment
		implements ChannelListener {

	public static final String TAG = ChannelManageFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	private ChannelViewModel viewModel;

	private BriarRecyclerView list;
	private final ChannelAdapter adapter = new ChannelAdapter(this);

	@Nullable
	private GroupId publishing = null;
	private boolean publishingWithFiles = false;
	@Nullable
	private ChannelItem publishingToFolder = null;
	@Nullable
	private GroupId editingMirrors = null;

	private final ActivityResultLauncher<String> publishLauncher =
			registerForActivityResult(new CreateDocumentAdvanced(),
					this::onPublishUriChosen);
	private final ActivityResultLauncher<Uri> folderLauncher =
			registerForActivityResult(new OpenDocumentTree(),
					this::onFolderChosen);
	private final ActivityResultLauncher<String[]> importLauncher =
			registerForActivityResult(new OpenAnyDocumentAdvanced(),
					this::onImportUriChosen);

	private void onPublishUriChosen(@Nullable Uri uri) {
		GroupId g = publishing;
		publishing = null;
		if (uri != null && g != null)
			viewModel.publish(g, uri, publishingWithFiles);
	}

	private void onFolderChosen(@Nullable Uri uri) {
		ChannelItem channel = publishingToFolder;
		publishingToFolder = null;
		if (uri != null && channel != null) {
			viewModel.publishToFolder(channel.getId(), uri,
					channel.getTitle());
		}
	}

	private void onImportUriChosen(@Nullable Uri uri) {
		if (uri != null) viewModel.importFile(uri);
	}

	public static ChannelManageFragment newInstance() {
		return new ChannelManageFragment();
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(ChannelViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.channels);
		View v = inflater.inflate(R.layout.fragment_channel_manage, container,
				false);

		list = v.findViewById(R.id.channelList);
		list.setLayoutManager(new LinearLayoutManager(getActivity()));
		list.setAdapter(adapter);

		viewModel.getChannels().observe(getViewLifecycleOwner(), result ->
				result.onError(e -> {
					list.setEmptyText(R.string.channels_manage_error);
					list.showData();
				}).onSuccess(channels -> {
					adapter.submitList(channels);
					if (requireNonNull(channels).isEmpty()) list.showData();
				})
		);
		viewModel.getChannelLink().observeEvent(getViewLifecycleOwner(),
				this::copyToClipboard);
		viewModel.getMessage().observeEvent(getViewLifecycleOwner(),
				this::showMessage);
		viewModel.getSubscribed().observeEvent(getViewLifecycleOwner(),
				this::openBlog);
		viewModel.getMirrors().observeEvent(getViewLifecycleOwner(),
				this::showMirrorsDialog);
		viewModel.getFetched().observeEvent(getViewLifecycleOwner(),
				this::showFetchResult);
		viewModel.getConfirmImport().observeEvent(getViewLifecycleOwner(),
				this::showConfirmImportDialog);
		return v;
	}

	private void showConfirmImportDialog(PendingImport pending) {
		AlertDialog.Builder b = new AlertDialog.Builder(requireContext(),
				R.style.BriarDialogTheme);
		b.setTitle(R.string.channels_import_confirm_title);
		b.setMessage(getString(R.string.channels_import_confirm_message,
				pending.name));
		b.setPositiveButton(R.string.channels_import_confirm_button,
				(d, w) -> viewModel.confirmImport(pending));
		b.setNegativeButton(R.string.cancel, null);
		b.show();
	}

	/**
	 * A file handed over can carry the channel's images and files, which
	 * gives the receiver everything at once but can be large, or only the
	 * posts, leaving the files to come later from mirrors or contacts.
	 */
	private void askWhetherToIncludeFiles(ChannelItem channel) {
		AlertDialog.Builder b = new AlertDialog.Builder(requireContext(),
				R.style.BriarDialogTheme);
		b.setTitle(R.string.channels_publish_with_files_title);
		b.setMessage(R.string.channels_publish_with_files_message);
		b.setPositiveButton(R.string.channels_publish_with_files,
				(d, w) -> saveToFile(channel, true));
		b.setNegativeButton(R.string.channels_publish_posts_only,
				(d, w) -> saveToFile(channel, false));
		b.show();
	}

	private void saveToFile(ChannelItem channel, boolean withFiles) {
		publishing = channel.getId();
		publishingWithFiles = withFiles;
		try {
			publishLauncher.launch(ChannelViewModel.fileName(channel.getTitle()));
		} catch (ActivityNotFoundException e) {
			showMessage(R.string.error_start_activity);
		}
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.channel_manage_actions, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			requireActivity().onBackPressed();
			return true;
		} else if (item.getItemId() == R.id.action_channel_create) {
			showCreateDialog();
			return true;
		} else if (item.getItemId() == R.id.action_channel_subscribe) {
			showSubscribeDialog();
			return true;
		} else if (item.getItemId() == R.id.action_channel_import) {
			try {
				importLauncher.launch(new String[] {"*/*"});
			} catch (ActivityNotFoundException e) {
				showMessage(R.string.error_start_activity);
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void showCreateDialog() {
		View v = requireActivity().getLayoutInflater()
				.inflate(R.layout.dialog_create_channel, null);
		EditText input = v.findViewById(R.id.channelTitle);
		AlertDialog.Builder b = new AlertDialog.Builder(requireContext(),
				R.style.BriarDialogTheme);
		b.setTitle(R.string.channels_create);
		b.setView(v);
		b.setPositiveButton(R.string.channels_create_button, (d, w) -> {
			String title = input.getText().toString().trim();
			if (!title.isEmpty()) viewModel.createChannel(title);
		});
		b.setNegativeButton(R.string.cancel, null);
		b.show();
	}

	@Override
	public void onChannelClick(ChannelItem channel) {
		openBlog(channel.getId());
	}

	@Override
	public void onActionsClick(ChannelItem channel, View anchor) {
		PopupMenu menu = new PopupMenu(requireContext(), anchor);
		menu.inflate(R.menu.channel_item_actions);
		// Only a channel's author can post to it, and only a reader can
		// unsubscribe from it. Anyone holding a channel can save it to a
		// file: the file carries only the channel's own signed messages,
		// so a copy made by a reader is exactly the copy the author would
		// have made, and if the author is out of reach it is the only way
		// the channel travels further without the internet
		boolean owned = channel.isOwned();
		menu.getMenu().findItem(R.id.action_channel_write).setVisible(owned);
		menu.getMenu().findItem(R.id.action_channel_delete).setVisible(owned);
		menu.getMenu().findItem(R.id.action_channel_unsubscribe)
				.setVisible(!owned);
		menu.setOnMenuItemClickListener(item -> {
			int id = item.getItemId();
			if (id == R.id.action_channel_write) {
				Intent i = new Intent(getActivity(),
						WriteBlogPostActivity.class);
				i.putExtra(GROUP_ID, channel.getId().getBytes());
				startActivity(i);
			} else if (id == R.id.action_channel_copy_link) {
				viewModel.copyLink(channel.getId());
			} else if (id == R.id.action_channel_publish) {
				askWhetherToIncludeFiles(channel);
			} else if (id == R.id.action_channel_publish_folder) {
				publishingToFolder = channel;
				try {
					folderLauncher.launch(null);
				} catch (ActivityNotFoundException e) {
					showMessage(R.string.error_start_activity);
				}
			} else if (id == R.id.action_channel_mirrors) {
				editingMirrors = channel.getId();
				viewModel.loadMirrors(channel.getId());
			} else if (id == R.id.action_channel_fetch) {
				viewModel.fetch(channel.getId());
			} else if (id == R.id.action_channel_delete) {
				confirmDelete(channel);
			} else if (id == R.id.action_channel_unsubscribe) {
				confirmUnsubscribe(channel);
			} else {
				return false;
			}
			return true;
		});
		menu.show();
	}

	private void confirmDelete(ChannelItem channel) {
		AlertDialog.Builder b = new AlertDialog.Builder(requireContext(),
				R.style.BriarDialogTheme);
		b.setTitle(R.string.channels_delete_title);
		b.setMessage(R.string.channels_delete_message);
		b.setPositiveButton(R.string.delete,
				(d, w) -> viewModel.deleteChannel(channel.getId()));
		b.setNegativeButton(R.string.cancel, null);
		b.show();
	}

	private void confirmUnsubscribe(ChannelItem channel) {
		AlertDialog.Builder b = new AlertDialog.Builder(requireContext(),
				R.style.BriarDialogTheme);
		b.setTitle(R.string.channels_unsubscribe_title);
		b.setMessage(R.string.channels_unsubscribe_message);
		b.setPositiveButton(R.string.channels_unsubscribe,
				(d, w) -> viewModel.unsubscribe(channel.getId()));
		b.setNegativeButton(R.string.cancel, null);
		b.show();
	}

	private void showSubscribeDialog() {
		View v = requireActivity().getLayoutInflater()
				.inflate(R.layout.dialog_subscribe_channel, null);
		EditText input = v.findViewById(R.id.channelLink);
		AlertDialog.Builder b = new AlertDialog.Builder(requireContext(),
				R.style.BriarDialogTheme);
		b.setTitle(R.string.channels_subscribe);
		b.setView(v);
		b.setPositiveButton(R.string.channels_subscribe_button, (d, w) -> {
			String link = input.getText().toString().trim();
			if (!link.isEmpty()) viewModel.subscribe(link);
		});
		b.setNegativeButton(R.string.cancel, null);
		b.show();
	}

	private void showMirrorsDialog(List<String> current) {
		GroupId g = editingMirrors;
		editingMirrors = null;
		if (g == null) return;
		View v = requireActivity().getLayoutInflater()
				.inflate(R.layout.dialog_channel_mirrors, null);
		EditText input = v.findViewById(R.id.channelMirrors);
		StringBuilder sb = new StringBuilder();
		for (String mirror : current) sb.append(mirror).append('\n');
		input.setText(sb.toString().trim());
		AlertDialog.Builder b = new AlertDialog.Builder(requireContext(),
				R.style.BriarDialogTheme);
		b.setTitle(R.string.channels_mirrors);
		b.setView(v);
		b.setPositiveButton(R.string.save_file, (d, w) -> {
			List<String> urls = new ArrayList<>();
			for (String line : input.getText().toString().split("\n")) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty()) urls.add(trimmed);
			}
			viewModel.setMirrors(g, urls);
		});
		b.setNegativeButton(R.string.cancel, null);
		b.show();
	}

	private void showFetchResult(FetchResult result) {
		switch (result.getOutcome()) {
			case NO_MIRRORS:
				showMessage(R.string.channels_fetch_no_mirrors);
				return;
			case UNREACHABLE:
				// Not the same as being up to date
				showMessage(R.string.channels_fetch_unreachable);
				return;
			case UNCHANGED:
				showMessage(R.string.channels_fetch_none);
				return;
			case TOO_LARGE:
				showMessage(R.string.channels_fetch_too_large);
				return;
			case IN_PROGRESS:
				showMessage(R.string.channels_fetch_in_progress);
				return;
			case FETCHED:
				int n = result.getMessages();
				Toast.makeText(requireContext(),
						getResources().getQuantityString(
								R.plurals.channels_fetch_posts, n, n),
						LENGTH_LONG).show();
		}
	}

	private void showMessage(int stringId) {
		Toast.makeText(requireContext(), stringId, LENGTH_LONG).show();
	}

	private void copyToClipboard(String link) {
		ClipboardManager cm = (ClipboardManager) requireContext()
				.getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm != null) {
			cm.setPrimaryClip(ClipData.newPlainText(
					getString(R.string.channels_copy_link), link));
			showMessage(R.string.channels_link_copied);
		}
	}

	private void openBlog(GroupId g) {
		Intent i = new Intent(getActivity(), BlogActivity.class);
		i.putExtra(GROUP_ID, g.getBytes());
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(i);
	}
}
