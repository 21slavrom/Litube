package com.hhst.youtubelite.player.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import org.junit.Test;

import java.net.SocketTimeoutException;
import java.util.Collections;

public class EngineRecoveryTest {

	private static HttpDataSource.InvalidResponseCodeException http403() {
		return new HttpDataSource.InvalidResponseCodeException(
						403, "Forbidden", null, Collections.emptyMap(),
						mock(DataSpec.class),
						new byte[0]);
	}

	private static HttpDataSource.InvalidResponseCodeException http500() {
		return new HttpDataSource.InvalidResponseCodeException(
						500, "Internal Server Error", null, Collections.emptyMap(),
						mock(DataSpec.class),
						new byte[0]);
	}

	@Test
	public void identifiesHttp403() {
		assertEquals(Engine.PlaybackRecoveryReason.HTTP_403,
						Engine.playbackRecoveryReason(http403()));
	}

	@Test
	public void returnsNullForNon403HttpResponse() {
		assertNull(Engine.playbackRecoveryReason(http500()));
	}

	@Test
	public void identifies403WrappedInCauseChain() {
		Exception wrapper = new RuntimeException(new IllegalStateException(http403()));
		assertEquals(Engine.PlaybackRecoveryReason.HTTP_403,
						Engine.playbackRecoveryReason(wrapper));
	}

	@Test
	public void identifiesConnectionOpenFailure() {
		HttpDataSource.HttpDataSourceException connectionError =
						new HttpDataSource.HttpDataSourceException(
										new SocketTimeoutException(),
										mock(DataSpec.class),
										HttpDataSource.HttpDataSourceException.TYPE_OPEN);
		assertEquals(Engine.PlaybackRecoveryReason.CONNECTION_OPEN_FAILED,
						Engine.playbackRecoveryReason(connectionError));
	}

	@Test
	public void returnsNullForUnrelatedException() {
		assertNull(Engine.playbackRecoveryReason(new RuntimeException("unrelated")));
	}
}
