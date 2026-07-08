package com.hhst.youtubelite.cast;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;

import java.util.List;

/**
 * Configures the Cast framework to use the default Styled Media Receiver, so no custom receiver
 * app needs to be registered or hosted. The receiver supports MPEG-DASH playback from the local
 * proxy.
 */
public final class CastOptionsProvider implements OptionsProvider {

	@Override
	@NonNull
	public CastOptions getCastOptions(@NonNull Context context) {
		return new CastOptions.Builder()
						.setReceiverApplicationId(com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
						.setStopReceiverApplicationWhenEndingSession(true)
						.build();
	}

	@Override
	public List<SessionProvider> getAdditionalSessionProviders(@NonNull Context context) {
		return null;
	}
}
