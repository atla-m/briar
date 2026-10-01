package org.briarproject.briar.android.blog;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import java.util.List;
import java.util.ArrayList;
import org.briarproject.briar.android.util.UiUtils;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.Pair;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.CheckBox;
import android.view.ViewGroup;
import android.widget.Toast;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.blog.BaseViewModel.ListUpdate;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.sharing.BlogSharingStatusActivity;
import org.briarproject.briar.android.sharing.ShareBlogActivity;
import org.briarproject.briar.android.util.BriarSnackbarBuilder;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.android.widget.LinkDialogFragment;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;

import static androidx.recyclerview.widget.RecyclerView.NO_POSITION;
import static android.app.Activity.RESULT_OK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.widget.Toast.LENGTH_SHORT;
import static com.google.android.material.snackbar.Snackbar.LENGTH_LONG;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.briar.android.activity.RequestCodes.REQUEST_SHARE_BLOG;

@UiThread
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class BlogFragment extends BaseFragment
		implements OnBlogPostClickListener {

	private final static String TAG = BlogFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private GroupId groupId;
	private BlogViewModel viewModel;
	private final BlogAttachmentBinder attachmentBinder =
			new BlogAttachmentBinder(this);
	private final BlogPostAdapter adapter = new BlogPostAdapter(false, this);
	private BriarRecyclerView list;

	static BlogFragment newInstance(GroupId groupId) {
		BlogFragment f = new BlogFragment();

		Bundle bundle = new Bundle();
		bundle.putByteArray(GROUP_ID, groupId.getBytes());

		f.setArguments(bundle);
		return f;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(BlogViewModel.class);
		attachmentBinder.setViewModel(viewModel);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		Bundle args = requireArguments();
		byte[] b = args.getByteArray(GROUP_ID);
		if (b == null) throw new IllegalStateException("No group ID in args");
		groupId = new GroupId(b);

		View v = inflater.inflate(R.layout.fragment_blog, container, false);

		list = v.findViewById(R.id.postList);
		View sharing = v.findViewById(R.id.channelSharing);
		shareWithContacts = v.findViewById(R.id.shareWithContacts);
		viewModel.getBlog().observe(getViewLifecycleOwner(), blog -> {
			// The two switches are shown on a channel, and only there
			sharing.setVisibility(
					blog.getBlog().isChannel() ? VISIBLE : GONE);
		});
		SwitchMaterial shareNearby = v.findViewById(R.id.shareNearby);
		shareNearby.setEnabled(true);
		viewModel.getNearbyExpiry().observe(getViewLifecycleOwner(),
				expiry -> showNearbyState(shareNearby, expiry));
		viewModel.getNearbyUnavailable().observeEvent(getViewLifecycleOwner(),
				unavailable -> Toast.makeText(requireContext(),
						R.string.channels_share_nearby_no_bluetooth,
						LENGTH_LONG).show());
		viewModel.getSharingWithContacts().observe(getViewLifecycleOwner(),
				on -> {
					if (shareWithContacts.isChecked() != on) {
						shareWithContacts.setOnCheckedChangeListener(null);
						shareWithContacts.setChecked(on);
					}
					shareWithContacts.setOnCheckedChangeListener(
							(view, checked) -> onShareWithContactsChanged(checked));
				});
		LayoutManager layoutManager = new LinearLayoutManager(getActivity());
		list.setLayoutManager(layoutManager);
		list.setAdapter(adapter);
		list.showProgressBar();
		list.setEmptyText(getString(R.string.blogs_other_blog_empty_state));

		viewModel.getBlogPosts().observe(getViewLifecycleOwner(), result ->
				result.onError(this::handleException)
						.onSuccess(this::onBlogPostsLoaded)
		);
		// redraw a post when one of its images or files has changed
		viewModel.getAttachmentUpdated().observe(getViewLifecycleOwner(), id -> {
			int position = adapter.findItemPosition(id);
			if (position != NO_POSITION) adapter.notifyItemChanged(position);
		});
		viewModel.getSaveError().observeEvent(getViewLifecycleOwner(),
				error -> Toast.makeText(requireContext(),
						error ? R.string.save_file_error
								: R.string.save_file_success,
						LENGTH_SHORT).show());

		viewModel.getBlogRemoved().observe(getViewLifecycleOwner(), removed -> {
			if (removed) finish();
		});
		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.blockAndClearNotifications();
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		viewModel.unblockNotifications();
		list.stopPeriodicUpdate();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.blogs_blog_actions, menu);
		MenuItem writeButton = menu.findItem(R.id.action_write_blog_post);
		MenuItem deleteButton = menu.findItem(R.id.action_blog_delete);
		MenuItem chooseContacts =
				menu.findItem(R.id.action_blog_choose_contacts);
		viewModel.getBlog().observe(getViewLifecycleOwner(), blog -> {
			if (blog.isOurs()) writeButton.setVisible(true);
			if (blog.canBeRemoved()) deleteButton.setEnabled(true);
			// A channel is unsubscribed from, or deleted by its owner
			if (blog.getBlog().isChannel()) {
				deleteButton.setTitle(blog.isOurs() ?
						R.string.channels_delete_channel :
						R.string.channels_unsubscribe_channel);
			}
		});
		// Only a channel that is passing posts to contacts has contacts
		// to choose
		viewModel.getSharingWithContacts().observe(getViewLifecycleOwner(),
				chooseContacts::setVisible);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_write_blog_post) {
			Intent i = new Intent(getActivity(), WriteBlogPostActivity.class);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivity(i);
			return true;
		} else if (itemId == R.id.action_blog_share) {
			Intent i = new Intent(getActivity(), ShareBlogActivity.class);
			i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivityForResult(i, REQUEST_SHARE_BLOG);
			return true;
		} else if (itemId == R.id.action_blog_sharing_status) {
			Intent i =
					new Intent(getActivity(), BlogSharingStatusActivity.class);
			i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivity(i);
			return true;
		} else if (itemId == R.id.action_blog_delete) {
			showDeleteDialog();
			return true;
		} else if (itemId == R.id.action_blog_choose_contacts) {
			showChooseContactsDialog(null);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onActivityResult(int request, int result,
			@Nullable Intent data) {
		super.onActivityResult(request, result, data);
		if (request == REQUEST_SHARE_BLOG && result == RESULT_OK) {
			displaySnackbar(R.string.blogs_sharing_snackbar, false);
		}
	}

	private final Handler handler = new Handler(Looper.getMainLooper());
	@Nullable
	private Runnable countdown = null;

	private void showNearbyState(SwitchMaterial shareNearby, long expiry) {
		long left = expiry - System.currentTimeMillis();
		boolean on = expiry > 0 && left > 0;
		if (shareNearby.isChecked() != on) {
			shareNearby.setOnCheckedChangeListener(null);
			shareNearby.setChecked(on);
		}
		shareNearby.setOnCheckedChangeListener(
				(view, checked) -> onShareNearbyChanged(checked));
		if (countdown != null) handler.removeCallbacks(countdown);
		if (on) {
			int minutes = (int) Math.max(1, (left + 59_999) / 60_000);
			// The switch's own label carries the countdown, to save space
			shareNearby.setText(getResources().getQuantityString(
					R.plurals.channels_share_nearby_on, minutes, minutes));
			countdown = () -> showNearbyState(shareNearby, expiry);
			handler.postDelayed(countdown, 30_000);
		} else {
			shareNearby.setText(R.string.channels_share_nearby);
		}
	}

	private void onShareNearbyChanged(boolean on) {
		if (!on) {
			viewModel.setSharingNearby(false);
			return;
		}
		// Say what it reveals before it is revealed
		MaterialAlertDialogBuilder builder =
				new MaterialAlertDialogBuilder(requireContext(),
						R.style.BriarDialogTheme);
		builder.setTitle(R.string.channels_share_nearby);
		builder.setMessage(R.string.channels_share_nearby_dialog);
		builder.setPositiveButton(R.string.channels_share_nearby_confirm,
				(d, w) -> viewModel.setSharingNearby(true));
		builder.setNegativeButton(R.string.cancel, (d, w) ->
				viewModel.setSharingNearby(false));
		builder.setOnCancelListener(d -> viewModel.setSharingNearby(false));
		builder.show();
	}

	@Override
	public void onDestroyView() {
		if (countdown != null) handler.removeCallbacks(countdown);
		super.onDestroyView();
	}

	private SwitchMaterial shareWithContacts;

	private void onShareWithContactsChanged(boolean on) {
		if (!on) {
			viewModel.setSharingWithContacts(false);
			return;
		}
		// Say what it reveals, and to whom, before it is revealed
		showChooseContactsDialog(shareWithContacts);
	}

	/**
	 * Shows the contacts with a box each, all ticked unless the user
	 * chose before, and turns sharing on for the ticked ones. If a
	 * switch is given, cancelling puts it back to off.
	 */
	private void showChooseContactsDialog(@Nullable SwitchMaterial sw) {
		viewModel.loadSharingContacts().observe(getViewLifecycleOwner(),
				contacts -> {
					View v = getLayoutInflater()
							.inflate(R.layout.dialog_channel_contacts, null);
					TextView explanation = v.findViewById(R.id.explanation);
					explanation.setText(sw == null ?
							R.string.channels_share_choose_contacts_explanation :
							R.string.channels_share_with_contacts_explanation);
					LinearLayout boxes = v.findViewById(R.id.contacts);
					TextView none = v.findViewById(R.id.noContacts);
					List<CheckBox> checks = new ArrayList<>();
					for (Pair<Contact, Boolean> p : contacts) {
						CheckBox box = new CheckBox(requireContext());
						box.setText(UiUtils.getContactDisplayName(p.getFirst()));
						box.setChecked(p.getSecond());
						box.setTag(p.getFirst().getId());
						boxes.addView(box);
						checks.add(box);
					}
					boolean empty = contacts.isEmpty();
					none.setVisibility(empty ? VISIBLE : GONE);
					v.findViewById(R.id.heading)
							.setVisibility(empty ? GONE : VISIBLE);
					Runnable cancel = () -> {
						if (sw != null) viewModel.setSharingWithContacts(false);
					};
					MaterialAlertDialogBuilder builder =
							new MaterialAlertDialogBuilder(requireContext(),
									R.style.BriarDialogTheme);
					builder.setTitle(R.string.channels_share_with_contacts);
					builder.setView(v);
					if (empty) {
						// Nothing to choose, so turning on would only store
						// an empty list; leave the switch off
						builder.setNegativeButton(R.string.ok,
								(d, w) -> cancel.run());
					} else {
						builder.setPositiveButton(
								R.string.channels_share_with_contacts_confirm,
								(d, w) -> {
									List<ContactId> chosen = new ArrayList<>();
									for (CheckBox box : checks) {
										if (box.isChecked()) {
											chosen.add((ContactId) box.getTag());
										}
									}
									viewModel.setSharingWithContacts(chosen);
								});
						builder.setNegativeButton(R.string.cancel,
								(d, w) -> cancel.run());
					}
					builder.setOnCancelListener(d -> cancel.run());
					builder.show();
				});
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	private void onBlogPostsLoaded(ListUpdate update) {
		adapter.submitList(update.getItems(), () -> {
			Boolean wasLocal = update.getPostAddedWasLocal();
			if (wasLocal != null && wasLocal) {
				list.scrollToPosition(0);
				displaySnackbar(R.string.blogs_blog_post_created,
						false);
			} else if (wasLocal != null) {
				displaySnackbar(R.string.blogs_blog_post_received,
						true);
			}
			viewModel.resetLocalUpdate();
			list.showData();
		});
	}

	@Override
	public void onBlogPostClick(BlogPostItem post) {
		BlogPostFragment f =
				BlogPostFragment.newInstance(groupId, post.getId());
		showNextFragment(f);
	}

	@Override
	public void onAuthorClick(BlogPostItem post) {
		if (post.getGroupId().equals(groupId) || getContext() == null) {
			// We're already there
			return;
		}
		Intent i = new Intent(getContext(), BlogActivity.class);
		i.putExtra(GROUP_ID, post.getGroupId().getBytes());
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		getContext().startActivity(i);
	}


	@Override
	public void onAttachmentClicked(View view, BlogPostItem post,
			AttachmentItem attachment) {
		attachmentBinder.onAttachmentClicked(view, post, attachment);
	}

	@Override
	public void onFileClick(BlogPostItem post, FileHeader header) {
		attachmentBinder.onFileClicked(post, header);
	}

	@Override
	public void onShareChannelClick(BlogPostItem post) {
		Intent i = new Intent(getActivity(), ShareBlogActivity.class);
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		i.putExtra(GROUP_ID, post.getGroupId().getBytes());
		startActivityForResult(i, REQUEST_SHARE_BLOG);
	}

	@Override
	public void onLinkClick(String url) {
		LinkDialogFragment f = LinkDialogFragment.newInstance(url);
		f.show(getParentFragmentManager(), f.getUniqueTag());
	}

	private void displaySnackbar(int stringId, boolean scroll) {
		BriarSnackbarBuilder sb = new BriarSnackbarBuilder();
		if (scroll) {
			sb.setAction(R.string.blogs_blog_post_scroll_to,
					v -> list.smoothScrollToPosition(0));
		}
		sb.make(list, stringId, LENGTH_LONG).show();
	}

	private void showDeleteDialog() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				requireContext(), R.style.BriarDialogTheme);
		builder.setTitle(getString(R.string.blogs_remove_blog));
		builder.setMessage(
				getString(R.string.blogs_remove_blog_dialog_message));
		builder.setPositiveButton(R.string.cancel, null);
		builder.setNegativeButton(R.string.blogs_remove_blog_ok,
				(dialog, which) -> deleteBlog());
		builder.show();
	}

	private void deleteBlog() {
		viewModel.deleteBlog();
		Toast.makeText(getActivity(), R.string.blogs_blog_removed, LENGTH_SHORT)
				.show();
		finish();
	}

}
