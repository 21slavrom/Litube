package com.hhst.youtubelite;

import android.app.Application;
import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.hhst.youtubelite.core.AppInit;
import com.hhst.youtubelite.extractor.potoken.PoTokenHost;
import com.tencent.mmkv.MMKV;

import java.io.File;

import javax.inject.Inject;

import dagger.hilt.android.HiltAndroidApp;

/**
 * Application entry point that initializes shared runtime state and logging.
 */
@HiltAndroidApp
public class App extends Application {

	@Inject
	PoTokenHost poTokenHost;

	@Override
	public void onCreate() {
		super.onCreate();
		MMKV.initialize(this);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			String processName = getProcessName();
			if (!getPackageName().equals(processName)) {
				WebView.setDataDirectorySuffix(processName);
			}
		}
		AppConstants.USER_AGENT = WebSettings.getDefaultUserAgent(this);
		startLogging();
	}

	private void startLogging() {
		File logFile = new File(getFilesDir(), AppConstants.LOGGING_FILENAME);
		AppInit.startLoggingAsync(logFile);
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
		// onTerminate is invoked only on emulators, not on production devices.
		// Still the canonical hook to release process-scoped native resources
		// (e.g. the PoToken WebView) so they don't leak across instrumentation runs.
		poTokenHost.release();
	}

}
