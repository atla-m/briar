package org.briarproject.briar.android.privategroup.conversation;

import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.attachment.ImageGridAdapter;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.attachment.FileRowBinder;
import org.briarproject.briar.android.threaded.BaseThreadItemViewHolder;
import org.briarproject.briar.android.threaded.ThreadItemAdapter.ThreadItemListener;
import org.briarproject.briar.android.view.AuthorView;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Date;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.recyclerview.widget.RecyclerView;

import static org.briarproject.briar.api.identity.AuthorInfo.Status.OURSELVES;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static androidx.core.content.ContextCompat.getColor;
import static org.briarproject.briar.api.identity.AuthorInfo.Status.OURSELVES;

/**
 * A private group post shown as a chat bubble: own posts on the end side,
 * other members' posts on the start side with the author's name, and a
 * quoted excerpt of the parent post for replies.
 */
@UiThread
@NotNullByDefault
class GroupPostViewHolder extends BaseThreadItemViewHolder<GroupMessageItem> {

	interface Listener extends ImageGridAdapter.Listener<GroupMessageItem> {

		/**
		 * Returns the post with the given ID if it's in the list, so a
		 * reply can quote it.
		 */
		@Nullable
		GroupMessageItem findItem(MessageId id);

		/**
		 * Called when the quoted parent of a reply is tapped.
		 */
		void onQuoteClick(MessageId parentId);

		/**
		 * Called when a file shared by a post is tapped.
		 */
		void onFileClick(GroupMessageItem item, FileHeader header);
	}

	private final LinearLayout bubble;
	private final AuthorView author;
	private final View quote, quoteBar;
	private final TextView quoteAuthor, quoteText, time;
	private final RecyclerView imageList;
	private final LinearLayout fileList;
	private final ImageGridAdapter<GroupMessageItem> imageAdapter;
	private final Listener listener;
	private final int marginTail, marginNonTail;

	GroupPostViewHolder(View v, Listener listener) {
		super(v);
		this.listener = listener;
		bubble = v.findViewById(R.id.bubble);
		author = v.findViewById(R.id.author);
		quote = v.findViewById(R.id.quote);
		quoteBar = v.findViewById(R.id.quoteBar);
		quoteAuthor = v.findViewById(R.id.quoteAuthor);
		// The bubble shows the time at the bottom, like a chat, so the date
		// in the author line would be redundant
		author.findViewById(R.id.dateView).setVisibility(GONE);
		quoteText = v.findViewById(R.id.quoteText);
		time = v.findViewById(R.id.time);
		imageList = v.findViewById(R.id.imageList);
		fileList = v.findViewById(R.id.fileList);
		imageAdapter = new ImageGridAdapter<>(v.getContext(), listener);
		imageList.setAdapter(imageAdapter);
		marginTail = v.getResources()
				.getDimensionPixelSize(R.dimen.message_bubble_margin_tail);
		marginNonTail = v.getResources()
				.getDimensionPixelSize(R.dimen.message_bubble_margin_non_tail);
	}

	@Override
	public void bind(GroupMessageItem item,
			ThreadItemListener<GroupMessageItem> threadListener) {
		super.bind(item, threadListener);

		// Own posts on the end side, others' on the start side
		boolean own = item.getAuthorInfo().getStatus() == OURSELVES;
		FrameLayout.LayoutParams params =
				(FrameLayout.LayoutParams) bubble.getLayoutParams();
		params.gravity = own ? Gravity.END : Gravity.START;
		params.setMarginStart(own ? marginNonTail : marginTail);
		params.setMarginEnd(own ? marginTail : marginNonTail);
		bubble.setLayoutParams(params);
		bubble.setBackgroundResource(own ? R.drawable.msg_out
				: R.drawable.msg_in);
		// The author's name is only needed for other members' posts
		author.setVisibility(own ? GONE : VISIBLE);
		time.setText(DateFormat.getTimeFormat(getContext())
				.format(new Date(item.getTimestamp())));
		// The quote's accent must stay visible on the coloured own bubble
		int accent = getColor(getContext(), own ? android.R.color.white
				: R.color.briar_primary);
		quoteBar.setBackgroundColor(accent);
		quoteAuthor.setTextColor(accent);

		// Quote the parent post, if this is a reply and the parent is known
		MessageId parentId = item.getParentId();
		GroupMessageItem parent =
				parentId == null ? null : listener.findItem(parentId);
		if (parent == null) {
			quote.setVisibility(GONE);
			quote.setOnClickListener(null);
		} else {
			quote.setVisibility(VISIBLE);
			quoteAuthor.setText(parent.getAuthorName());
			String excerpt;
			if (parent.hasText()) excerpt = parent.getText();
			else if (!parent.getFileHeaders().isEmpty())
				excerpt = parent.getFileHeaders().get(0).getName();
			else excerpt = getContext().getString(R.string.groups_quote_photo);
			quoteText.setText(excerpt);
			quote.setOnClickListener(
					v -> listener.onQuoteClick(parent.getId()));
		}

		textView.setVisibility(item.hasText() ? VISIBLE : GONE);
		if (item.getAttachmentHeaders().isEmpty()) {
			imageList.setVisibility(GONE);
			imageAdapter.clear();
		} else {
			imageList.setVisibility(VISIBLE);
			// A single image is shown at its thumbnail size so the bubble
			// hugs it; a grid of several images sizes itself
			ViewGroup.LayoutParams lp = imageList.getLayoutParams();
			if (item.getAttachments().size() == 1) {
				AttachmentItem a = item.getAttachments().get(0);
				lp.width = a.getThumbnailWidth();
				lp.height = a.getThumbnailHeight();
			} else {
				lp.width = WRAP_CONTENT;
				lp.height = WRAP_CONTENT;
			}
			imageList.setLayoutParams(lp);
			imageAdapter.setMessageItem(item);
		}
		bindFiles(item);

		// Tapping the bubble starts a reply to this post. The text view has
		// a movement method for links, which consumes its taps, so it needs
		// its own listener; taps on links still open the link instead.
		bubble.setOnClickListener(v -> threadListener.onReplyClick(item));
		textView.setOnClickListener(v -> threadListener.onReplyClick(item));
	}

	private void bindFiles(GroupMessageItem item) {
		FileRowBinder.bind(fileList, item.getFileHeaders(), item::getFileStatus,
				h -> listener.onFileClick(item, h),
				item.getAuthorInfo().getStatus() == OURSELVES);
	}

}
