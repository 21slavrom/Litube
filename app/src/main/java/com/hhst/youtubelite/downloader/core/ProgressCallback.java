package com.hhst.youtubelite.downloader.core;

import java.io.File;

/**
 * Callback for download progress, completion, and merge events.
 * Stream-level callers may leave {@link #onMerge()} as the default no-op.
 */
public interface ProgressCallback {

	void onProgress(int progress, long downloadedBytes, long totalBytes);

	void onComplete(File file);

	void onError(Exception error);

	void onCancel();

	default void onMerge() {
	}

}
