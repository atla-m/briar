package org.briarproject.briar.android.conversation;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import org.briarproject.briar.R;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.android.attachment.FileRowBinder;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT;
import static androidx.core.content.ContextCompat.getColor;
import static androidx.core.widget.ImageViewCompat.setImageTintList;

@UiThread
@NotNullByDefault
class ConversationMessageViewHolder extends ConversationItemViewHolder {

	private final ImageAdapter adapter;
	private final ViewGroup statusLayout;
	private final LinearLayout fileList;
	private final View textView;
	private final int timeColor, timeColorBubble;
	private final ConstraintSet textConstraints = new ConstraintSet();
	private final ConstraintSet imageConstraints = new ConstraintSet();
	private final ConstraintSet imageTextConstraints = new ConstraintSet();

	ConversationMessageViewHolder(View v, ConversationListener listener,
			boolean isIncoming, RecycledViewPool imageViewPool,
			ImageItemDecoration imageItemDecoration) {
		super(v, listener, isIncoming);
		statusLayout = v.findViewById(R.id.statusLayout);
		fileList = v.findViewById(R.id.fileList);
		textView = v.findViewById(R.id.text);

		// image list
		RecyclerView list = v.findViewById(R.id.imageList);
		list.setRecycledViewPool(imageViewPool);
		adapter = new ImageAdapter(v.getContext(), listener);
		list.setAdapter(adapter);
		list.addItemDecoration(imageItemDecoration);

		// remember original status text color
		timeColor = time.getCurrentTextColor();
		timeColorBubble =
				getColor(v.getContext(), R.color.msg_status_bubble_foreground);

		// clone constraint sets from layout files
		textConstraints.clone(v.getContext(),
				R.layout.list_item_conversation_msg_in_content);
		imageConstraints.clone(v.getContext(),
				R.layout.list_item_conversation_msg_image);
		imageTextConstraints.clone(v.getContext(),
				R.layout.list_item_conversation_msg_image_text);

		// in/out are different layouts, so we need to do this only once
		textConstraints
				.setHorizontalBias(R.id.statusLayout, isIncoming() ? 1 : 0);
		imageConstraints
				.setHorizontalBias(R.id.statusLayout, isIncoming() ? 1 : 0);
		imageTextConstraints
				.setHorizontalBias(R.id.statusLayout, isIncoming() ? 1 : 0);
	}

	@Override
	void bind(ConversationItem conversationItem, boolean selected) {
		super.bind(conversationItem, selected);
		ConversationMessageItem item =
				(ConversationMessageItem) conversationItem;
		if (!item.getFileHeaders().isEmpty()) {
			bindFileItem(item);
		} else if (item.getAttachments().isEmpty()) {
			bindTextItem();
		} else {
			bindImageItem(item);
		}
	}

	private void bindTextItem() {
		resetStatusLayoutForText();
		textConstraints.applyTo(layout);
		adapter.clear();
		fileList.setVisibility(GONE);
		textView.setVisibility(VISIBLE);
	}

	private void bindFileItem(ConversationMessageItem item) {
		// Files are laid out like text: rows above the optional caption
		resetStatusLayoutForText();
		textConstraints.applyTo(layout);
		adapter.clear();
		textView.setVisibility(item.getText() == null ? GONE : VISIBLE);
		FileRowBinder.bind(fileList, item.getFileHeaders(),
				item::getFileStatus, h -> listener.onFileClick(item, h),
				!isIncoming());
	}

	private void bindImageItem(ConversationMessageItem item) {
		ConstraintSet constraintSet;
		if (item.getText() == null) {
			statusLayout.setBackgroundResource(R.drawable.msg_status_bubble);
			time.setTextColor(timeColorBubble);
			setImageTintList(bomb, ColorStateList.valueOf(timeColorBubble));
			constraintSet = imageConstraints;
		} else {
			resetStatusLayoutForText();
			constraintSet = imageTextConstraints;
		}

		if (item.getAttachments().size() == 1) {
			// apply image size constraints for a single image
			AttachmentItem attachment = item.getAttachments().get(0);
			int width = attachment.getThumbnailWidth();
			int height = attachment.getThumbnailHeight();
			constraintSet.constrainWidth(R.id.imageList, width);
			constraintSet.constrainHeight(R.id.imageList, height);
		} else {
			// bubble adapts to size of image list
			constraintSet.constrainWidth(R.id.imageList, WRAP_CONTENT);
			constraintSet.constrainHeight(R.id.imageList, WRAP_CONTENT);
		}
		constraintSet.applyTo(layout);
		adapter.setConversationItem(item);
		fileList.setVisibility(GONE);
		textView.setVisibility(item.getText() == null ? GONE : VISIBLE);
	}

	private void resetStatusLayoutForText() {
		statusLayout.setBackgroundResource(0);
		// also reset padding (the background drawable defines some)
		statusLayout.setPadding(0, 0, 0, 0);
		time.setTextColor(timeColor);
		setImageTintList(bomb, ColorStateList.valueOf(timeColor));
	}

}
