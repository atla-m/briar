package org.briarproject.briar.android.privategroup.conversation;

import android.view.View;

import org.briarproject.briar.R;
import org.briarproject.briar.android.threaded.ThreadItemAdapter.ThreadItemListener;
import org.briarproject.briar.android.threaded.ThreadPostViewHolder;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;
import androidx.recyclerview.widget.RecyclerView;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

/**
 * A view holder for private group posts, which may have image attachments.
 */
@UiThread
@NotNullByDefault
class GroupPostViewHolder extends ThreadPostViewHolder<GroupMessageItem> {

	private final RecyclerView imageList;
	private final GroupImageAdapter imageAdapter;

	GroupPostViewHolder(View v, GroupImageAdapter.Listener imageListener) {
		super(v);
		imageList = v.findViewById(R.id.imageList);
		imageAdapter = new GroupImageAdapter(v.getContext(), imageListener);
		imageList.setAdapter(imageAdapter);
	}

	@Override
	public void bind(GroupMessageItem item,
			ThreadItemListener<GroupMessageItem> listener) {
		super.bind(item, listener);
		textView.setVisibility(item.hasText() ? VISIBLE : GONE);
		if (item.getAttachmentHeaders().isEmpty()) {
			imageList.setVisibility(GONE);
			imageAdapter.clear();
		} else {
			imageList.setVisibility(VISIBLE);
			imageAdapter.setMessageItem(item);
		}
	}

}
