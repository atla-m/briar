package org.briarproject.briar.android.blog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import static org.briarproject.briar.android.util.UiUtils.formatDate;

@NotNullByDefault
class ChannelAdapter
		extends ListAdapter<Channel, ChannelAdapter.ChannelViewHolder> {

	private final ChannelListener listener;

	ChannelAdapter(ChannelListener listener) {
		super(new DiffUtil.ItemCallback<Channel>() {
			@Override
			public boolean areItemsTheSame(Channel a, Channel b) {
				return a.getBlogId().equals(b.getBlogId());
			}

			@Override
			public boolean areContentsTheSame(Channel a, Channel b) {
				return a.getTitle().equals(b.getTitle());
			}
		});
		this.listener = listener;
	}

	@Override
	public ChannelViewHolder onCreateViewHolder(ViewGroup parent,
			int viewType) {
		View v = LayoutInflater.from(parent.getContext()).inflate(
				R.layout.list_item_channel, parent, false);
		return new ChannelViewHolder(v);
	}

	@Override
	public void onBindViewHolder(ChannelViewHolder ui, int position) {
		ui.bindItem(getItem(position));
	}

	class ChannelViewHolder extends RecyclerView.ViewHolder {

		private final Context ctx;
		private final View layout;
		private final TextView title, created;
		private final ImageButton write, delete;

		private ChannelViewHolder(View v) {
			super(v);
			ctx = v.getContext();
			layout = v;
			title = v.findViewById(R.id.titleView);
			created = v.findViewById(R.id.createdView);
			write = v.findViewById(R.id.writeButton);
			delete = v.findViewById(R.id.deleteButton);
		}

		private void bindItem(Channel item) {
			title.setText(item.getTitle());
			created.setText(formatDate(ctx, item.getCreated()));
			write.setOnClickListener(v -> listener.onWriteClick(item));
			delete.setOnClickListener(v -> listener.onDeleteClick(item));
			layout.setOnClickListener(v -> listener.onChannelClick(item));
		}
	}

	interface ChannelListener {

		void onChannelClick(Channel channel);

		void onWriteClick(Channel channel);

		void onDeleteClick(Channel channel);
	}
}
