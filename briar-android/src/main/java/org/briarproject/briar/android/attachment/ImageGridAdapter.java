package org.briarproject.briar.android.attachment;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import com.bumptech.glide.load.Transformation;

import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.conversation.glide.BriarImageTransformation;
import org.briarproject.briar.android.conversation.glide.GlideApp;
import org.briarproject.briar.android.conversation.glide.Radii;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager.LayoutParams;

import static android.content.Context.WINDOW_SERVICE;
import static android.widget.ImageView.ScaleType.CENTER_CROP;
import static android.widget.ImageView.ScaleType.FIT_CENTER;
import static com.bumptech.glide.load.engine.DiskCacheStrategy.NONE;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static java.util.Objects.requireNonNull;
import static org.briarproject.briar.android.attachment.AttachmentItem.State.AVAILABLE;
import static org.briarproject.briar.android.attachment.AttachmentItem.State.ERROR;

/**
 * Shows the images carried by a message in a two-column grid. Used by
 * private group posts and blog posts alike; the message type is the
 * adapter's parameter so that each screen's listener keeps its own type.
 */
@UiThread
@NotNullByDefault
public class ImageGridAdapter<I extends ImageGridAdapter.Item>
		extends RecyclerView.Adapter<ImageGridAdapter<I>.Holder> {

	@DrawableRes
	private static final int ERROR_RES = R.drawable.ic_image_broken;

	/**
	 * A message that carries images, which the grid draws.
	 */
	public interface Item {

		MessageId getId();

		List<AttachmentItem> getAttachments();
	}

	public interface Listener<I extends Item> {
		void onAttachmentClicked(View view, I item, AttachmentItem attachment);
	}

	private final List<AttachmentItem> items = new ArrayList<>();
	private final Listener<I> listener;
	private final int imageSize;
	private final Radii radii;
	@Nullable
	private I messageItem;

	public ImageGridAdapter(Context ctx, Listener<I> listener) {
		this.listener = listener;
		imageSize = getImageSize(ctx);
		int radius = ctx.getResources()
				.getDimensionPixelSize(R.dimen.message_bubble_radius_small);
		radii = new Radii(radius, radius, radius, radius);
	}

	@Override
	public Holder onCreateViewHolder(ViewGroup parent, int type) {
		View v = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.list_item_image, parent, false);
		return new Holder(v);
	}

	@Override
	public void onBindViewHolder(Holder holder, int position) {
		I messageItem = requireNonNull(this.messageItem);
		AttachmentItem item = items.get(position);
		holder.itemView.setOnClickListener(v ->
				listener.onAttachmentClicked(v, messageItem, item));
		int size = items.size();
		// the last of an odd number of images spans both columns
		boolean singleInRow = size % 2 != 0 && position == size - 1;
		holder.bind(item, messageItem, size == 1, singleInRow);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	public void setMessageItem(I item) {
		messageItem = item;
		items.clear();
		items.addAll(item.getAttachments());
		notifyDataSetChanged();
	}

	public void clear() {
		messageItem = null;
		items.clear();
		notifyDataSetChanged();
	}

	private int getImageSize(Context ctx) {
		Resources res = ctx.getResources();
		WindowManager windowManager =
				(WindowManager) ctx.getSystemService(WINDOW_SERVICE);
		int maxSize = res.getDimensionPixelSize(
				R.dimen.message_bubble_image_max_width);
		if (windowManager == null) {
			return Math.min(maxSize, res.getDimensionPixelSize(
					R.dimen.message_bubble_image_default));
		}
		DisplayMetrics displayMetrics = new DisplayMetrics();
		windowManager.getDefaultDisplay().getMetrics(displayMetrics);
		return Math.min(displayMetrics.widthPixels / 3, maxSize);
	}

	public class Holder extends RecyclerView.ViewHolder {

		private final ImageView imageView;

		Holder(View v) {
			super(v);
			imageView = v.findViewById(R.id.imageView);
		}

		void bind(AttachmentItem a, I messageItem, boolean single,
				boolean singleInRow) {
			LayoutParams params = (LayoutParams) imageView.getLayoutParams();
			int width = singleInRow ? imageSize * 2 : imageSize;
			params.width = single ? a.getThumbnailWidth() : width;
			params.height = single ? a.getThumbnailHeight() : imageSize;
			params.setFullSpan(!single && singleInRow);
			imageView.setLayoutParams(params);

			if (a.getState() != AVAILABLE) {
				GlideApp.with(imageView).clear(imageView);
				imageView.setImageResource(a.getState() == ERROR ? ERROR_RES
						: R.drawable.ic_image_missing);
				imageView.setScaleType(FIT_CENTER);
			} else {
				Transformation<Bitmap> transformation =
						new BriarImageTransformation(radii);
				GlideApp.with(imageView)
						.load(a.getHeader())
						.diskCacheStrategy(NONE)
						.error(ERROR_RES)
						.transform(transformation)
						.transition(withCrossFade())
						.into(imageView)
						.waitForLayout();
				imageView.setScaleType(CENTER_CROP);
			}
			imageView.setTransitionName(
					a.getTransitionName(messageItem.getId()));
		}
	}

}
