package org.briarproject.briar.android.forward;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.contactselection.ContactSelectorActivity;
import org.briarproject.briar.android.controller.handler.UiExceptionHandler;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;

import static android.widget.Toast.LENGTH_SHORT;
import static org.briarproject.briar.android.blog.BlogPostFragment.POST_ID;

/**
 * Sends a channel post to chosen contacts as a private message carrying
 * the channel's link. Unlike a reblog it signs nothing into a blog of our
 * own, so nobody downstream learns that the post passed through us, and
 * unlike sharing the channel it is a single post rather than a
 * subscription. There is no covering note: passing the post on is the
 * whole of the intention.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ForwardPostActivity extends ContactSelectorActivity {

	@Inject
	ForwardPostController controller;

	private MessageId postId;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle bundle) {
		super.onCreate(bundle);

		Intent i = getIntent();
		byte[] g = i.getByteArrayExtra(GROUP_ID);
		if (g == null) throw new IllegalStateException("No GroupId");
		groupId = new GroupId(g);
		byte[] p = i.getByteArrayExtra(POST_ID);
		if (p == null) throw new IllegalStateException("No MessageId");
		postId = new MessageId(p);

		if (bundle == null) {
			showInitialFragment(ForwardPostFragment.newInstance(groupId));
		}
	}

	@UiThread
	@Override
	public void contactsSelected(Collection<ContactId> contacts) {
		super.contactsSelected(contacts);
		controller.forward(groupId, postId, contacts,
				new UiExceptionHandler<DbException>(this) {
					@Override
					public void onExceptionUi(DbException exception) {
						Toast.makeText(ForwardPostActivity.this,
								R.string.blogs_forward_error, LENGTH_SHORT)
								.show();
						handleException(exception);
					}
				});
		Toast.makeText(this, R.string.blogs_forward_sent, LENGTH_SHORT).show();
		setResult(RESULT_OK);
		supportFinishAfterTransition();
	}
}
