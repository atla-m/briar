package org.briarproject.briar.android.blog;

import android.content.Context;
import android.content.Intent;
import android.text.Spanned;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.forward.ForwardPostActivity;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.attachment.FileRowBinder;
import org.briarproject.briar.android.attachment.ImageGridAdapter;
import org.briarproject.briar.android.view.AuthorView;
import org.briarproject.briar.api.blog.BlogCommentHeader;
import org.briarproject.briar.api.blog.BlogPostHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import static org.briarproject.briar.api.identity.AuthorInfo.Status.OURSELVES;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.briar.android.blog.BlogPostFragment.POST_ID;
import static org.briarproject.briar.android.util.UiUtils.TEASER_LENGTH;
import static org.briarproject.briar.android.util.UiUtils.getSpanned;
import static org.briarproject.briar.android.util.UiUtils.getTeaser;
import static org.briarproject.briar.android.util.UiUtils.makeLinksClickable;
import static org.briarproject.briar.android.view.AuthorView.COMMENTER;
import static org.briarproject.briar.android.view.AuthorView.REBLOGGER;
import static org.briarproject.briar.android.view.AuthorView.RSS_FEED_REBLOGGED;

@UiThread
@NotNullByDefault
class BlogPostViewHolder extends RecyclerView.ViewHolder {

	private final Context ctx;
	private final ViewGroup layout;
	private final AuthorView reblogger;
	private final AuthorView author;
	private final ImageButton reblogButton;
	private final TextView text;
	private final RecyclerView imageList;
	private final LinearLayout fileList;
	private final TextView attachmentsNotCarried;
	private final ImageGridAdapter<BlogPostItem> imageAdapter;
	private final ViewGroup commentContainer;
	private final boolean fullText, authorClickable;
	private final int padding;

	private final OnBlogPostClickListener listener;

	BlogPostViewHolder(View v, boolean fullText,
			OnBlogPostClickListener listener, boolean authorClickable) {
		super(v);
		this.fullText = fullText;
		this.listener = listener;
		this.authorClickable = authorClickable;

		ctx = v.getContext();
		layout = v.findViewById(R.id.postLayout);
		reblogger = v.findViewById(R.id.rebloggerView);
		author = v.findViewById(R.id.authorView);
		reblogButton = v.findViewById(R.id.commentView);
		text = v.findViewById(R.id.textView);
		imageList = v.findViewById(R.id.imageList);
		fileList = v.findViewById(R.id.fileList);
		attachmentsNotCarried = v.findViewById(R.id.attachmentsNotCarried);
		imageAdapter = new ImageGridAdapter<>(ctx, listener);
		imageList.setAdapter(imageAdapter);
		commentContainer = v.findViewById(R.id.commentContainer);
		padding = ctx.getResources()
				.getDimensionPixelSize(R.dimen.listitem_vertical_margin);
	}

	void hideReblogButton() {
		reblogButton.setVisibility(GONE);
	}

	void updateDate(long time) {
		author.setDate(time);
	}

	void setTransitionName(MessageId id) {
		ViewCompat.setTransitionName(layout, getTransitionName(id));
	}

	private String getTransitionName(MessageId id) {
		return "blogPost" + id.hashCode();
	}

	void bindItem(BlogPostItem item) {
		setTransitionName(item.getId());
		if (!fullText) {
			layout.setClickable(true);
			layout.setOnClickListener(v -> listener.onBlogPostClick(item));
		}

		boolean isReblog = item instanceof BlogCommentItem;

		// author and date
		BlogPostHeader post = item.getPostHeader();
		author.setAuthor(post.getAuthor(), post.getAuthorInfo());
		author.setDate(post.getTimestamp());
		author.setPersona(
				item.isRssFeed() ? AuthorView.RSS_FEED : AuthorView.NORMAL);
		// TODO make author clickable more often #624
		if (authorClickable && !isReblog) {
			author.setAuthorClickable(v -> listener.onAuthorClick(item));
		} else {
			author.setAuthorNotClickable();
		}

		// post text
		Spanned postText = getSpanned(item.getText());
		if (fullText) {
			text.setText(postText);
			text.setTextIsSelectable(true);
			makeLinksClickable(text, listener::onLinkClick);
		} else {
			text.setTextIsSelectable(false);
			if (postText.length() > TEASER_LENGTH)
				postText = getTeaser(ctx, postText);
			text.setText(postText);
		}

		// the images and files the post carries
		bindAttachments(item);
		FileRowBinder.bind(fileList, item.getFileHeaders(),
				item::getFileStatus, h -> listener.onFileClick(item, h),
				item.getAuthorInfo().getStatus() == OURSELVES);
		// A reblogged post is a signed copy of the original, so it names
		// the images and files the original carried, but they live in the
		// blog it came from and can't be shown here
		int notCarried = item.getPostHeader().getAttachmentsNotCarried();
		if (notCarried > 0) {
			attachmentsNotCarried.setText(ctx.getResources().getQuantityString(
					R.plurals.blogs_reblog_attachments_not_carried, notCarried,
					notCarried));
			attachmentsNotCarried.setVisibility(VISIBLE);
		} else {
			attachmentsNotCarried.setVisibility(GONE);
		}

		// A channel post is shared by sharing the channel. Reblogging it
		// would sign it into the reblogger's own blog, attaching their
		// identity to it and carrying that to everyone downstream
		if (item.isChannel()) {
			reblogButton.setImageResource(R.drawable.social_share_white);
			reblogButton.setContentDescription(
					ctx.getString(R.string.blogs_share_channel));
			// Two different things to pass on, so ask which: the channel,
			// which keeps sending the recipient its posts, or this one
			// post, which is a message and nothing more
			reblogButton.setOnClickListener(v -> {
				CharSequence[] options = {
						ctx.getString(R.string.blogs_share_channel),
						ctx.getString(R.string.blogs_forward_post)
				};
				new AlertDialog.Builder(ctx, R.style.BriarDialogTheme)
						.setItems(options, (d, which) -> {
							if (which == 0) {
								listener.onShareChannelClick(item);
							} else {
								Intent i = new Intent(ctx,
										ForwardPostActivity.class);
								i.putExtra(GROUP_ID,
										item.getGroupId().getBytes());
								i.putExtra(POST_ID, item.getId().getBytes());
								ctx.startActivity(i);
							}
						})
						.show();
			});
		} else {
			reblogButton.setImageResource(R.drawable.ic_repeat);
			reblogButton.setContentDescription(
					ctx.getString(R.string.blogs_reblog_button));
			reblogButton.setOnClickListener(v -> {
				Intent i = new Intent(ctx, ReblogActivity.class);
				i.putExtra(GROUP_ID, item.getGroupId().getBytes());
				i.putExtra(POST_ID, item.getId().getBytes());
				ctx.startActivity(i);
			});
		}

		// comments
		commentContainer.removeAllViews();
		if (isReblog) {
			onBindComment((BlogCommentItem) item, authorClickable);
		} else {
			reblogger.setVisibility(GONE);
		}
	}

	private void bindAttachments(BlogPostItem item) {
		if (item.getAttachmentHeaders().isEmpty()) {
			imageList.setVisibility(GONE);
			imageAdapter.clear();
			return;
		}
		imageList.setVisibility(VISIBLE);
		// A single image is shown at its own size; a grid of several
		// images sizes itself
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

	private void onBindComment(BlogCommentItem item, boolean authorClickable) {
		// reblogger
		reblogger.setAuthor(item.getAuthor(), item.getAuthorInfo());
		reblogger.setDate(item.getTimestamp());
		if (authorClickable) {
			reblogger.setAuthorClickable(v -> listener.onAuthorClick(item));
		} else {
			reblogger.setAuthorNotClickable();
		}
		reblogger.setVisibility(VISIBLE);
		reblogger.setPersona(REBLOGGER);

		author.setPersona(item.getHeader().getRootPost().isRssFeed() ?
				RSS_FEED_REBLOGGED : COMMENTER);

		// comments
		// TODO use nested RecyclerView instead like we do for Image Attachments
		for (BlogCommentHeader c : item.getComments()) {
			View v = LayoutInflater.from(ctx).inflate(
					R.layout.list_item_blog_comment, commentContainer, false);

			AuthorView author = v.findViewById(R.id.authorView);
			TextView text = v.findViewById(R.id.textView);

			author.setAuthor(c.getAuthor(), c.getAuthorInfo());
			author.setDate(c.getTimestamp());
			// TODO make author clickable #624

			text.setText(c.getComment());
			Linkify.addLinks(text, Linkify.WEB_URLS);
			text.setMovementMethod(null);
			if (fullText) {
				text.setTextIsSelectable(true);
				makeLinksClickable(text, listener::onLinkClick);
			}

			commentContainer.addView(v);
		}
	}
}
