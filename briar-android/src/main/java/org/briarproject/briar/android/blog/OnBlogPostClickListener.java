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
}
