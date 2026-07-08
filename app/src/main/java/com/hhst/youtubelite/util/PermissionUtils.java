package com.hhst.youtubelite.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * Permission helpers for storage and notifications.
 */
public final class PermissionUtils {
	public static final int REQUEST_POST_NOTIFICATIONS = 100;
	public static final int REQUEST_STORAGE_PERMISSION = 2001;

	/**
	 * Literal permission string for runtime requests. Using the constant
	 * {@link Manifest.permission#POST_NOTIFICATIONS} here would trigger the
	 * InlinedApi lint rule because the field is annotated as added in API 33,
	 * even though the value is identical on every API level.
	 */
	private static final String POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS";

	private PermissionUtils() {
	}

	public static boolean needsPostNotificationsPermission() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
	}

	public static boolean hasPostNotificationsPermission(@NonNull Context context) {
		if (!needsPostNotificationsPermission()) return true;
		return ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION)
						== PackageManager.PERMISSION_GRANTED;
	}

	@NonNull
	public static String[] postNotificationsPermission() {
		return new String[]{POST_NOTIFICATIONS_PERMISSION};
	}

	public static boolean needsLegacyStoragePermission() {
		return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
	}

	public static boolean hasDownloadStoragePermission(@NonNull Context context) {
		if (!needsLegacyStoragePermission()) return true;
		return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
						== PackageManager.PERMISSION_GRANTED;
	}

	@NonNull
	public static String[] downloadStoragePermissions() {
		return new String[]{
						Manifest.permission.READ_EXTERNAL_STORAGE,
						Manifest.permission.WRITE_EXTERNAL_STORAGE
		};
	}
}
