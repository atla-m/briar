package org.briarproject.briar.android.conversation;

import android.view.View;

import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;

@UiThread
@NotNullByDefault
interface ConversationListener {

	void respondToRequest(ConversationRequestItem item, boolean accept);

	void openRequestedShareable(ConversationRequestItem item);

	void onAttachmentClicked(View view, ConversationMessageItem messageItem,
			AttachmentItem attachmentItem);

	void onFileClick(ConversationMessageItem messageItem, FileHeader header);

	void onAutoDeleteTimerNoticeClicked();

	/**
	 * Called when the notice above a forwarded channel post is tapped,
	 * which offers to subscribe to the channel it came from.
	 */
	void onForwardedChannelClick(String channelLink);

	void onLinkClick(String url);

}
