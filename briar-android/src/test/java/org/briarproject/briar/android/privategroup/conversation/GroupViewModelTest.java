package org.briarproject.briar.android.privategroup.conversation;

import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.junit.Test;

import static org.briarproject.bramble.test.TestUtils.getAuthor;
import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
import static org.briarproject.briar.android.privategroup.conversation.GroupViewModel.canPost;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.GROUP_SALT_LENGTH;
import static org.briarproject.briar.api.privategroup.PrivateGroupManager.CLIENT_ID;
import static org.briarproject.briar.api.privategroup.PrivateGroupManager.MAJOR_VERSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GroupViewModelTest {

	private final PrivateGroup ordinary = privateGroup(false);
	private final PrivateGroup creatorOnly = privateGroup(true);

	private static PrivateGroup privateGroup(boolean creatorOnly) {
		return new PrivateGroup(getGroup(CLIENT_ID, MAJOR_VERSION), "Group",
				getAuthor(), getRandomBytes(GROUP_SALT_LENGTH), creatorOnly);
	}

	/**
	 * The group, our role in it and the dissolved flag are loaded by
	 * separate database tasks, and the dissolved flag arrives first. If we
	 * answered from whichever inputs had turned up, a member of a group
	 * only the creator can post in would see the composer and then watch
	 * it disappear.
	 */
	@Test
	public void testDoesNotAnswerUntilAllThreeInputsAreKnown() {
		assertNull(canPost(null, null, null));
		// The dissolved flag arrives first, on its own
		assertNull(canPost(null, null, false));
		// Then the group, but not yet our role in it
		assertNull(canPost(creatorOnly, null, false));
		// Only now can the question be answered
		assertEquals(false, canPost(creatorOnly, false, false));
	}

	@Test
	public void testOnlyTheCreatorCanPostInACreatorOnlyGroup() {
		assertEquals(true, canPost(creatorOnly, true, false));
		assertEquals(false, canPost(creatorOnly, false, false));
	}

	@Test
	public void testAnyMemberCanPostInAnOrdinaryGroup() {
		assertEquals(true, canPost(ordinary, true, false));
		assertEquals(true, canPost(ordinary, false, false));
	}

	@Test
	public void testNobodyCanPostInADissolvedGroup() {
		assertEquals(false, canPost(ordinary, true, true));
		assertEquals(false, canPost(ordinary, false, true));
		assertEquals(false, canPost(creatorOnly, true, true));
		assertEquals(false, canPost(creatorOnly, false, true));
	}
}
