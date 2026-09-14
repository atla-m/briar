package org.briarproject.briar.android.blog;

import android.app.Application;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.LiveResult;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.now;

@NotNullByDefault
class ChannelViewModel extends DbViewModel {

	private static final Logger LOG =
			getLogger(ChannelViewModel.class.getName());

	private final ChannelManager channelManager;
	private final Executor dbExecutor;

	private final MutableLiveData<LiveResult<List<Channel>>> channels =
			new MutableLiveData<>();
	private final MutableLiveEvent<GroupId> channelCreated =
			new MutableLiveEvent<>();

	@Inject
	ChannelViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor, ChannelManager channelManager) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.dbExecutor = dbExecutor;
		this.channelManager = channelManager;
		loadChannels();
	}

	LiveData<LiveResult<List<Channel>>> getChannels() {
		return channels;
	}

	LiveEvent<GroupId> getChannelCreated() {
		return channelCreated;
	}

	private void loadChannels() {
		loadFromDb(this::loadChannels, channels::setValue);
	}

	@DatabaseExecutor
	private List<Channel> loadChannels(Transaction txn) throws DbException {
		long start = now();
		List<Channel> loaded = channelManager.getChannels(txn);
		logDuration(LOG, "Loading channels", start);
		return loaded;
	}

	void createChannel(String title) {
		dbExecutor.execute(() -> {
			try {
				Channel channel = channelManager.createChannel(title);
				loadChannels();
				channelCreated.postEvent(channel.getBlogId());
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void deleteChannel(GroupId g) {
		dbExecutor.execute(() -> {
			try {
				channelManager.deleteChannel(g);
				loadChannels();
			} catch (DbException e) {
				handleException(e);
			}
		});
	}
}
