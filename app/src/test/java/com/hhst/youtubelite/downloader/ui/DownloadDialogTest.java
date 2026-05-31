package com.hhst.youtubelite.downloader.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;
import java.util.Locale;

public class DownloadDialogTest {

	@Test
	public void sortVideoChoices_ordersUserVisibleQualities() {
		VideoStream p720 = video("720p24", 24, 20, 1_000_000);
		VideoStream p1080 = video("1080p60", 60, 50, 2_000_000);
		VideoStream p2160 = video("2160p", 30, 30, 3_000_000);

		List<VideoStream> sorted = DownloadDialog.sortVideoChoices(List.of(p720, p1080, p2160));

		assertSame(p2160, sorted.get(0));
		assertSame(p1080, sorted.get(1));
		assertSame(p720, sorted.get(2));
	}

	@Test
	public void videoDownloadChoices_collapsesDuplicateVisibleOptions() {
		VideoStream first = video("720p30", 30, 10, 400_000_000);
		VideoStream duplicate = video("720p30", 30, 20, 400_000_000);
		VideoStream higherFps = video("720p60", 60, 30, 800_000_000);

		List<VideoStream> choices = DownloadDialog.videoDownloadChoices(List.of(first, duplicate, higherFps));

		assertEquals(2, choices.size());
		assertSame(first, choices.get(0));
		assertSame(higherFps, choices.get(1));
	}

	@Test
	public void audioTrackChoices_collapsesDuplicateTracksButKeepsDifferentLanguages() {
		AudioStream english = audio("en", "English", Locale.ENGLISH, AudioTrackType.ORIGINAL);
		AudioStream duplicateEnglish = audio("en", "English", Locale.ENGLISH, AudioTrackType.ORIGINAL);
		AudioStream korean = audio("ko", "Korean", Locale.KOREAN, AudioTrackType.DUBBED);

		List<AudioStream> choices = DownloadDialog.audioTrackChoices(List.of(english, duplicateEnglish, korean));

		assertEquals(2, choices.size());
		assertSame(english, choices.get(0));
		assertSame(korean, choices.get(1));
	}

	private static VideoStream video(String resolution, int fps, int itag, long contentLength) {
		VideoStream stream = mock(VideoStream.class);
		ItagItem item = mock(ItagItem.class);
		when(item.getContentLength()).thenReturn(contentLength);
		when(stream.getFormat()).thenReturn(MediaFormat.MPEG_4);
		when(stream.getResolution()).thenReturn(resolution);
		when(stream.getHeight()).thenReturn(0);
		when(stream.getFps()).thenReturn(fps);
		when(stream.getBitrate()).thenReturn(1_000_000);
		when(stream.getItag()).thenReturn(itag);
		when(stream.getItagItem()).thenReturn(item);
		return stream;
	}

	private static AudioStream audio(String id, String name, Locale locale, AudioTrackType type) {
		AudioStream stream = mock(AudioStream.class);
		ItagItem item = mock(ItagItem.class);
		when(item.getContentLength()).thenReturn(1_000_000L);
		when(stream.getFormat()).thenReturn(MediaFormat.M4A);
		when(stream.getAudioTrackId()).thenReturn(id);
		when(stream.getAudioTrackName()).thenReturn(name);
		when(stream.getAudioLocale()).thenReturn(locale);
		when(stream.getAudioTrackType()).thenReturn(type);
		when(stream.getAverageBitrate()).thenReturn(128);
		when(stream.getItagItem()).thenReturn(item);
		return stream;
	}
}
