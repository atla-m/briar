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
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.blog.ChannelAdapter.ChannelListener;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.util.ActivityLaunchers.CreateDocumentAdvanced;
import org.briarproject.briar.android.util.ActivityLaunchers.OpenAnyDocumentAdvanced;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
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
	@Nullable
	private GroupId editingMirrors = null;

	private final ActivityResultLauncher<String> publishLauncher =
			registerForActivityResult(new CreateDocumentAdvanced(),
					this::onPublishUriChosen);
	private final ActivityResultLauncher<String[]> importLauncher =
			registerForActivityResult(new OpenAnyDocumentAdvanced(),
					this::onImportUriChosen);

	private void onPublishUriChosen(@Nullable Uri uri) {
		GroupId g = publishing;
		publishing = null;
		if (uri != null && g != null) viewModel.publish(g, uri);
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
		return v;
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
	public void onChannelClick(Channel channel) {
		openBlog(channel.getBlogId());
	}

	@Override
	public void onActionsClick(Channel channel, View anchor) {
		PopupMenu menu = new PopupMenu(requireContext(), anchor);
		menu.inflate(R.menu.channel_item_actions);
		menu.setOnMenuItemClickListener(item -> {
			int id = item.getItemId();
			if (id == R.id.action_channel_write) {
				Intent i = new Intent(getActivity(),
						WriteBlogPostActivity.class);
				i.putExtra(GROUP_ID, channel.getBlogId().getBytes());
				startActivity(i);
			} else if (id == R.id.action_channel_copy_link) {
				viewModel.copyLink(channel.getBlogId());
			} else if (id == R.id.action_channel_publish) {
				publishing = channel.getBlogId();
				try {
					publishLauncher.launch(channel.getTitle() + ".briar");
				} catch (ActivityNotFoundException e) {
					showMessage(R.string.error_start_activity);
				}
			} else if (id == R.id.action_channel_mirrors) {
				editingMirrors = channel.getBlogId();
				viewModel.loadMirrors(channel.getBlogId());
			} else if (id == R.id.action_channel_fetch) {
				viewModel.fetch(channel.getBlogId());
			} else if (id == R.id.action_channel_delete) {
				confirmDelete(channel);
			} else {
				return false;
			}
			return true;
		});
		menu.show();
	}

	private void confirmDelete(Channel channel) {
		AlertDialog.Builder b = new AlertDialog.Builder(requireContext(),
				R.style.BriarDialogTheme);
		b.setTitle(R.string.channels_delete_title);
		b.setMessage(R.string.channels_delete_message);
		b.setPositiveButton(R.string.delete,
				(d, w) -> viewModel.deleteChannel(channel.getBlogId()));
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

	private void showFetchResult(int count) {
		if (count == 0) {
			showMessage(R.string.channels_fetch_none);
		} else {
			Toast.makeText(requireContext(), getResources().getQuantityString(
					R.plurals.channels_fetch_posts, count, count),
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
