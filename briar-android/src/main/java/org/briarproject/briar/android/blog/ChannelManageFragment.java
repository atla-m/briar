package org.briarproject.briar.android.blog;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.blog.ChannelAdapter.ChannelListener;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
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
		}
		return super.onOptionsItemSelected(item);
	}

	private void showCreateDialog() {
		View v = requireActivity().getLayoutInflater()
				.inflate(R.layout.dialog_create_channel, null);
		android.widget.EditText input = v.findViewById(R.id.channelTitle);
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
	public void onWriteClick(Channel channel) {
		Intent i = new Intent(getActivity(), WriteBlogPostActivity.class);
		i.putExtra(GROUP_ID, channel.getBlogId().getBytes());
		startActivity(i);
	}

	@Override
	public void onDeleteClick(Channel channel) {
		AlertDialog.Builder b = new AlertDialog.Builder(requireContext(),
				R.style.BriarDialogTheme);
		b.setTitle(R.string.channels_delete_title);
		b.setMessage(R.string.channels_delete_message);
		b.setPositiveButton(R.string.delete,
				(d, w) -> viewModel.deleteChannel(channel.getBlogId()));
		b.setNegativeButton(R.string.cancel, null);
		b.show();
	}

	private void openBlog(GroupId g) {
		Intent i = new Intent(getActivity(), BlogActivity.class);
		i.putExtra(GROUP_ID, g.getBytes());
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(i);
	}
}
