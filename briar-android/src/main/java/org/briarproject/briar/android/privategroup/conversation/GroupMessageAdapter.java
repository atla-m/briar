package org.briarproject.briar.android.privategroup.conversation;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.attachment.ImageGridAdapter;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.threaded.BaseThreadItemViewHolder;
import org.briarproject.briar.android.threaded.ThreadItemAdapter;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.LayoutRes;
import androidx.annotation.UiThread;

@UiThread
@NotNullByDefault
class GroupMessageAdapter extends ThreadItemAdapter<GroupMessageItem>
		implements GroupPostViewHolder.Listener {

	private final ImageGridAdapter.Listener<GroupMessageItem> imageListener;
	private final QuoteListener quoteListener;
	private final FileListener fileListener;

	private boolean isCreator = false;
	interface QuoteListener {
		void onQuoteClick(MessageId parentId);
	}

	interface FileListener {
		void onFileClick(GroupMessageItem item, FileHeader header);
	}

	GroupMessageAdapter(ThreadItemListener<GroupMessageItem> listener,
			ImageGridAdapter.Listener<GroupMessageItem> imageListener,
			QuoteListener quoteListener, FileListener fileListener) {
		super(listener);
		this.imageListener = imageListener;
		this.quoteListener = quoteListener;
		this.fileListener = fileListener;
	}

	@LayoutRes
	@Override
	public int getItemViewType(int position) {
		GroupMessageItem item = getItem(position);
		return item.getLayout();
	}

	@Override
	public BaseThreadItemViewHolder<GroupMessageItem> onCreateViewHolder(
			ViewGroup parent, int type) {
		View v = LayoutInflater.from(parent.getContext())
				.inflate(type, parent, false);
		if (type == R.layout.list_item_group_join_notice) {
			return new JoinMessageItemViewHolder(v, isCreator);
		}
		return new GroupPostViewHolder(v, this);
	}

	@Override
	@Nullable
	public GroupMessageItem findItem(MessageId id) {
		for (GroupMessageItem item : getCurrentList()) {
			if (item.getId().equals(id)) return item;
		}
		return null;
	}

	@Override
	public void onQuoteClick(MessageId parentId) {
		quoteListener.onQuoteClick(parentId);
	}

	@Override
	public void onFileClick(GroupMessageItem item, FileHeader header) {
		fileListener.onFileClick(item, header);
	}

	@Override
	public void onAttachmentClicked(View view, GroupMessageItem item,
			AttachmentItem attachment) {
		imageListener.onAttachmentClicked(view, item, attachment);
	}

	void setIsCreator(boolean isCreator) {
		this.isCreator = isCreator;
		notifyDataSetChanged();
	}

}
