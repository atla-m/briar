package org.briarproject.briar.android.forward;

import android.os.Bundle;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.contactselection.ContactSelectorController;
import org.briarproject.briar.android.contactselection.ContactSelectorFragment;
import org.briarproject.briar.android.contactselection.SelectableContactItem;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ForwardPostFragment extends ContactSelectorFragment {

	public static final String TAG = ForwardPostFragment.class.getName();

	@Inject
	ForwardPostController controller;

	public static ForwardPostFragment newInstance(GroupId groupId) {
		Bundle args = new Bundle();
		args.putByteArray(GROUP_ID, groupId.getBytes());
		ForwardPostFragment fragment = new ForwardPostFragment();
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	protected ContactSelectorController<SelectableContactItem> getController() {
		return controller;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}
