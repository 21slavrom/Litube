package com.hhst.youtubelite.downloader.ui;

import androidx.annotation.NonNull;

public interface DownloadPermissionHost {
	void requestDownloadStoragePermission(@NonNull Runnable onGranted);
}
