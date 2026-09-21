package org.briarproject.briar.android.privategroup.creation;

import org.briarproject.briar.android.fragment.BaseFragment.BaseFragmentListener;

interface CreateGroupListener extends BaseFragmentListener {

	/**
	 * @param name The name chosen for the new group
	 * @param creatorOnly True if only the creator should be able to post
	 * in the new group
	 */
	void onGroupNameChosen(String name, boolean creatorOnly);
}
