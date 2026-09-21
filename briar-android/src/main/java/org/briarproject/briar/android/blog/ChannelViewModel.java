package org.briarproject.briar.android.blog;

import android.app.Application;

import android.net.Uri;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.sync.event.GroupAddedEvent;
import org.briarproject.bramble.api.sync.event.GroupRemovedEvent;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.viewmodel.DbViewModel;
import org.briarproject.briar.android.viewmodel.LiveEvent;
import org.briarproject.briar.android.viewmodel.LiveResult;
import org.briarproject.briar.android.viewmodel.MutableLiveEvent;
import org.briarproject.briar.R;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.briar.api.channel.Channel;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.FetchResult;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.now;

@NotNullByDefault
class ChannelViewModel extends DbViewModel implements EventListener {

	private static final Logger LOG =
			getLogger(ChannelViewModel.class.getName());

	private final ChannelManager channelManager;
	private final EventBus eventBus;
	private final Executor dbExecutor;
	private final Executor ioExecutor;

	private final MutableLiveData<LiveResult<List<ChannelItem>>> channels =
			new MutableLiveData<>();
	private final MutableLiveEvent<GroupId> channelCreated =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<GroupId> subscribed =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<String> channelLink =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<Integer> message =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<FetchResult> fetched =
			new MutableLiveEvent<>();
	private final MutableLiveEvent<List<String>> mirrors =
			new MutableLiveEvent<>();

	@Inject
	ChannelViewModel(Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor,
			@IoExecutor Executor ioExecutor,
			ChannelManager channelManager, EventBus eventBus) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.dbExecutor = dbExecutor;
		this.ioExecutor = ioExecutor;
		this.channelManager = channelManager;
		this.eventBus = eventBus;
		eventBus.addListener(this);
		loadChannels();
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		// A channel can be subscribed to or removed from somewhere else
		// entirely, such as tapping a forwarded post in a conversation, so
		// the list can't only refresh when this screen does the work
		if (e instanceof GroupAddedEvent || e instanceof GroupRemovedEvent) {
			loadChannels();
		}
	}

	LiveData<LiveResult<List<ChannelItem>>> getChannels() {
		return channels;
	}

	LiveEvent<GroupId> getChannelCreated() {
		return channelCreated;
	}

	private void loadChannels() {
		loadFromDb(this::loadChannels, channels::setValue);
	}

	@DatabaseExecutor
	private List<ChannelItem> loadChannels(Transaction txn)
			throws DbException {
		long start = now();
		List<ChannelItem> loaded = new ArrayList<>();
		for (Channel c : channelManager.getChannels(txn)) {
			loaded.add(ChannelItem.owned(c));
		}
		// Channels we only read are listed too, so their reader can fetch
		// them and see where they are published
		for (Blog b : channelManager.getSubscriptions(txn)) {
			loaded.add(ChannelItem.subscribed(b));
		}
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

	LiveEvent<String> getChannelLink() {
		return channelLink;
	}

	LiveEvent<Integer> getMessage() {
		return message;
	}

	void copyLink(GroupId g) {
		dbExecutor.execute(() -> {
			try {
				channelLink.postEvent(channelManager.getChannelLink(g));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void subscribe(String link) {
		dbExecutor.execute(() -> {
			try {
				Blog blog = channelManager.subscribeFromLink(link);
				// Show it in the list straight away, not only after the
				// screen is next opened
				loadChannels();
				subscribed.postEvent(blog.getId());
			} catch (FormatException e) {
				message.postEvent(R.string.channels_subscribe_error);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveEvent<GroupId> getSubscribed() {
		return subscribed;
	}

	void publish(GroupId g, Uri uri) {
		ioExecutor.execute(() -> {
			try (OutputStream out = getApplication().getContentResolver()
					.openOutputStream(uri)) {
				if (out == null) throw new IOException("Cannot open " + uri);
				channelManager.exportChannel(g, out);
				message.postEvent(R.string.channels_published);
			} catch (IOException | DbException e) {
				logException(LOG, WARNING, e);
				message.postEvent(R.string.channels_publish_error);
			}
		});
	}

	void importFile(Uri uri) {
		ioExecutor.execute(() -> {
			try (InputStream in = getApplication().getContentResolver()
					.openInputStream(uri)) {
				if (in == null) throw new IOException("Cannot open " + uri);
				channelManager.importChannel(in);
				message.postEvent(R.string.channels_imported);
			} catch (IOException | DbException e) {
				logException(LOG, WARNING, e);
				message.postEvent(R.string.channels_import_error);
			}
		});
	}

	LiveEvent<List<String>> getMirrors() {
		return mirrors;
	}

	void loadMirrors(GroupId g) {
		dbExecutor.execute(() -> {
			try {
				mirrors.postEvent(channelManager.getMirrors(g));
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void setMirrors(GroupId g, List<String> urls) {
		dbExecutor.execute(() -> {
			try {
				channelManager.setMirrors(g, urls);
				message.postEvent(R.string.channels_mirrors_saved);
			} catch (IllegalArgumentException e) {
				message.postEvent(R.string.channels_mirrors_invalid);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	void fetch(GroupId g) {
		ioExecutor.execute(() -> {
			try {
				fetched.postEvent(channelManager.fetchChannel(g));
			} catch (DbException e) {
				message.postEvent(R.string.channels_fetch_error);
			}
		});
	}

	LiveEvent<FetchResult> getFetched() {
		return fetched;
	}

	void unsubscribe(GroupId g) {
		dbExecutor.execute(() -> {
			try {
				channelManager.unsubscribe(g);
				loadChannels();
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
