package org.briarproject.briar.android.attachment;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.api.attachment.FileHeader;
import org.briarproject.briar.api.attachment.FileStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

/**
 * Fills a container with one row per file shared by a message: icon, name,
 * and either the arrival progress or what a tap does. Used by private
 * groups and private conversations alike.
 */
@UiThread
@NotNullByDefault
public class FileRowBinder {

	public interface StatusLookup {
		@Nullable
		FileStatus getFileStatus(FileHeader header);
	}

	public interface Listener {
		void onFileClick(FileHeader header);
	}

	/**
	 * Returns true if the app can play the file itself; other files can
	 * only be saved.
	 */
	public static boolean isPlayable(String contentType) {
		return contentType.startsWith("audio/") ||
				contentType.startsWith("video/");
	}

	public static void bind(LinearLayout fileList, List<FileHeader> headers,
			StatusLookup statuses, Listener listener) {
		fileList.removeAllViews();
		if (headers.isEmpty()) {
			fileList.setVisibility(GONE);
			return;
		}
		fileList.setVisibility(VISIBLE);
		Context ctx = fileList.getContext();
		LayoutInflater inflater = LayoutInflater.from(ctx);
		for (FileHeader h : headers) {
			View row = inflater.inflate(R.layout.list_item_group_file,
					fileList, false);
			ImageView icon = row.findViewById(R.id.fileIcon);
			TextView name = row.findViewById(R.id.fileName);
			TextView status = row.findViewById(R.id.fileStatus);
			ProgressBar progress = row.findViewById(R.id.fileProgress);
			icon.setImageResource(getFileIcon(h.getContentType()));
			name.setText(h.getName());
			String size = Formatter.formatShortFileSize(ctx, h.getSize());
			FileStatus s = statuses.getFileStatus(h);
			if (s != null && s.isComplete()) {
				int action = isPlayable(h.getContentType())
						? R.string.file_tap_to_play : R.string.file_tap_to_save;
				status.setText(size + " \u00b7 " + ctx.getString(action));
				progress.setVisibility(GONE);
			} else {
				int received = s == null ? 0 : s.getChunksReceived();
				int total = h.getChunkCount();
				status.setText(ctx.getString(R.string.file_receiving, received,
						total));
				progress.setVisibility(VISIBLE);
				progress.setProgress(total == 0 ? 0 : 100 * received / total);
			}
			row.setOnClickListener(v -> listener.onFileClick(h));
			fileList.addView(row);
		}
	}

	private static int getFileIcon(String contentType) {
		if (contentType.startsWith("audio/")) return R.drawable.ic_audio_file;
		if (contentType.startsWith("video/")) return R.drawable.ic_video_file;
		return R.drawable.ic_generic_file;
	}
}
