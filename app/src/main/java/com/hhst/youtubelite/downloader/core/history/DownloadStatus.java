package com.hhst.youtubelite.downloader.core.history;

/**
 * Lifecycle state of a download record.
 */
public enum DownloadStatus {
	RUNNING,
	QUEUED,
	MERGING,
	COMPLETED,
	FAILED,
	CANCELED,
	PAUSED
}
