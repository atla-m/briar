package org.briarproject.briar.android.blog;

import org.briarproject.briar.android.attachment.ImageGridAdapter;
import org.briarproject.briar.api.attachment.FileHeader;

interface OnBlogPostClickListener
		extends ImageGridAdapter.Listener<BlogPostItem> {

	void onBlogPostClick(BlogPostItem post);

	void onAuthorClick(BlogPostItem post);

	void onLinkClick(String url);

	/**
	 * Called when one of the files a post shares is tapped.
	 */
	void onFileClick(BlogPostItem post, FileHeader header);

	/**
	 * Called when the share button on a channel post is tapped. Channel
	 * posts are shared by choosing contacts to exchange the channel's
	 * posts with, not by reblogging or by invitation.
	 */
	void onShareChannelClick(BlogPostItem post);
}
