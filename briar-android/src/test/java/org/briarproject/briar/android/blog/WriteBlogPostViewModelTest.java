package org.briarproject.briar.android.blog;

import android.app.Application;
import android.net.Uri;

import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.ImmediateExecutor;
import org.briarproject.briar.android.AndroidExecutorTestImpl;
import org.briarproject.briar.android.attachment.AttachmentCreator;
import org.briarproject.briar.android.attachment.AttachmentCreatorFactory;
import org.briarproject.briar.android.attachment.AttachmentStore;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.StreamSource;
import org.briarproject.briar.api.blog.BlogManager;
import org.briarproject.briar.api.blog.BlogPostFactory;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.briar.api.channel.ChannelManager;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.Executor;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.core.app.ApplicationProvider;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.briarproject.bramble.test.TestUtils.getRandomId;

/**
 * A file is stored as soon as the user picks it, so this screen is
 * responsible for deleting one that no post ever comes to reveal.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21)
public class WriteBlogPostViewModelTest extends BrambleMockTestCase {

	@Rule
	public final InstantTaskExecutorRule testRule =
			new InstantTaskExecutorRule();

	private final Executor executor = new ImmediateExecutor();
	private final LifecycleManager lifecycleManager =
			context.mock(LifecycleManager.class);
	private final TransactionManager db =
			context.mock(TransactionManager.class);
	private final IdentityManager identityManager =
			context.mock(IdentityManager.class);
	private final BlogManager blogManager = context.mock(BlogManager.class);
	private final BlogPostFactory blogPostFactory =
			context.mock(BlogPostFactory.class);
	private final ChannelManager channelManager =
			context.mock(ChannelManager.class);
	private final AttachmentCreatorFactory attachmentCreatorFactory =
			context.mock(AttachmentCreatorFactory.class);
	private final AttachmentCreator attachmentCreator =
			context.mock(AttachmentCreator.class);
	private final Clock clock = context.mock(Clock.class);

	private final GroupId groupId = new GroupId(getRandomId());
	private final MessageId postId = new MessageId(getRandomId());
	private final FileHeader fileHeader = new FileHeader(groupId,
			new MessageId(getRandomId()), "file.octet-stream",
			"application/octet-stream", 1234);
	private final Uri uri = Uri.parse("content://test/file");

	private WriteBlogPostViewModel viewModel;

	private void createViewModel() throws Exception {
		context.checking(new Expectations() {{
			allowing(lifecycleManager).waitForDatabase();
			oneOf(attachmentCreatorFactory)
					.create(with(any(AttachmentStore.class)));
			will(returnValue(attachmentCreator));
		}});
		Application app = ApplicationProvider.getApplicationContext();
		viewModel = new WriteBlogPostViewModel(app, executor,
				lifecycleManager, db, new AndroidExecutorTestImpl(executor),
				executor, identityManager, blogManager, blogPostFactory,
				channelManager, attachmentCreatorFactory, clock);
		viewModel.setGroupId(groupId);
	}

	private void expectAttachFile() throws Exception {
		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(1L));
			oneOf(blogManager).addLocalFile(with(groupId), with(1L),
					with(any(String.class)), with(any(String.class)),
					with(any(StreamSource.class)));
			will(returnValue(fileHeader));
		}});
	}

	@Test
	public void testDeletesAnAttachedFileWhenThePostIsAbandoned()
			throws Exception {
		createViewModel();
		expectAttachFile();
		viewModel.attachFile(uri);

		context.checking(new Expectations() {{
			oneOf(attachmentCreator).cancel();
			// No post was published, so nothing will ever reveal the file
			// and nothing else will ever delete it
			oneOf(blogManager).removeFile(fileHeader);
		}});
		viewModel.onCleared();
	}

	@Test
	public void testKeepsAnAttachedFileWhenThePostIsPublished()
			throws Exception {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		createViewModel();
		expectAttachFile();
		viewModel.attachFile(uri);

		Channel channel = context.mock(Channel.class);
		context.checking(new Expectations() {{
			oneOf(channelManager).getChannel(groupId);
			will(returnValue(channel));
			oneOf(channelManager).post(groupId, "text", emptyList(),
					singletonList(fileHeader));
			will(returnValue(postId));
			oneOf(attachmentCreator).onAttachmentsSent(postId);
		}});
		viewModel.publish("text", emptyList());

		context.checking(new Expectations() {{
			oneOf(attachmentCreator).cancel();
			// The post references the file, so it is no longer ours
			never(blogManager).removeFile(with(any(FileHeader.class)));
		}});
		viewModel.onCleared();
	}

	@Test
	public void testDeletesAFileStoredAfterThePostIsAbandoned()
			throws Exception {
		createViewModel();
		context.checking(new Expectations() {{
			oneOf(attachmentCreator).cancel();
		}});
		viewModel.onCleared();

		expectAttachFile();
		context.checking(new Expectations() {{
			// The screen went away while the file was being stored
			oneOf(blogManager).removeFile(fileHeader);
		}});
		viewModel.attachFile(uri);
	}
}
