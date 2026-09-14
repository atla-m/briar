package org.briarproject.briar.android.blog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.briar.android.util.UiUtils.formatDate;

@NotNullByDefault
class ChannelAdapter
		extends ListAdapter<ChannelItem, ChannelAdapter.ChannelViewHolder> {

	private final ChannelListener listener;

	ChannelAdapter(ChannelListener listener) {
		super(new DiffUtil.ItemCallback<ChannelItem>() {
			@Override
			public boolean areItemsTheSame(ChannelItem a, ChannelItem b) {
				return a.getId().equals(b.getId());
			}

			@Override
			public boolean areContentsTheSame(ChannelItem a, ChannelItem b) {
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
		private final TextView title, created, createdLabel;
		private final ImageButton overflow;

		private ChannelViewHolder(View v) {
			super(v);
			ctx = v.getContext();
			layout = v;
			title = v.findViewById(R.id.titleView);
			created = v.findViewById(R.id.createdView);
			createdLabel = v.findViewById(R.id.created);
			overflow = v.findViewById(R.id.overflowButton);
		}

		private void bindItem(ChannelItem item) {
			title.setText(item.getTitle());
			if (item.isOwned()) {
				created.setText(formatDate(ctx, item.getCreated()));
				created.setVisibility(VISIBLE);
				createdLabel.setVisibility(VISIBLE);
			} else {
				// We don't know when someone else made their channel
				created.setVisibility(GONE);
				createdLabel.setVisibility(GONE);
			}
			overflow.setOnClickListener(
					v -> listener.onActionsClick(item, v));
			layout.setOnClickListener(v -> listener.onChannelClick(item));
		}
	}

	interface ChannelListener {

		void onChannelClick(ChannelItem channel);

		void onActionsClick(ChannelItem channel, View anchor);
	}
}
