package com.hhst.youtubelite.downloader.core.impl;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hhst.youtubelite.downloader.core.LiteDownloader;
import com.hhst.youtubelite.downloader.core.MediaMuxer;
import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.hhst.youtubelite.downloader.core.StreamDownloader;
import com.hhst.youtubelite.downloader.core.Task;

import org.apache.commons.io.FileUtils;
import org.schabi.newpipe.extractor.stream.Stream;

import java.io.File;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Runs file downloads and merge callbacks.
 */
@Singleton
public class LiteDownloaderImpl implements LiteDownloader {
	private final Context context;
	private final StreamDownloader streamDownloader;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final Map<String, Task> tasks = new ConcurrentHashMap<>();
	private final Map<String, ProgressCallback> callbacks = new ConcurrentHashMap<>();

	@Inject
	public LiteDownloaderImpl(@ApplicationContext Context context,
	                          StreamDownloader streamDownloader) {
		this.context = context;
		this.streamDownloader = streamDownloader;
	}

	@Override
	public void setCallback(@NonNull String videoId, ProgressCallback callback) {
		if (callback != null) callbacks.put(videoId, callback);
		else callbacks.remove(videoId);
	}

	@Override
	public void download(@NonNull Task task) {
		tasks.put(task.videoId(), task);
		if (task.subtitle() != null) {
			exec(task, () -> FileUtils.copyURLToFile(new URL(task.subtitle().getContent()), outputFile(task)));
		} else if (task.thumbnail() != null) {
			exec(task, () -> FileUtils.copyURLToFile(new URL(task.thumbnail()), outputFile(task)));
		} else {
			downloadMedia(task);
		}
	}

	private void exec(Task task, ThrowingRunnable run) {
		CompletableFuture.runAsync(() -> {
			try {
				run.run();
				complete(task.videoId(), outputFile(task));
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, executor).exceptionally(e -> handleError(task, e));
	}

	private void downloadMedia(Task task) {
		streamDownloader.setMaxThreadCount(task.threadCount());
		File videoFile = tempFile(task, "_v"), audioFile = tempFile(task, "_a"), output = outputFile(task);
		long videoSize = contentLength(task.video()), audioSize = contentLength(task.audio());

		Aggregator aggregator = new Aggregator(videoSize, audioSize, (progress, downloaded, total) -> progress(task.videoId(), progress, downloaded, total));

		CompletableFuture<File> videoFuture = task.video() == null ? null : streamDownloader.download(task.video().getContent(), videoFile, createProgressAdapter(progress -> {
			if (audioSize > 0) aggregator.updateVideo(progress);
			else progress(task.videoId(), progress, (long) (videoSize * (progress / 100.0)), videoSize);
		}));

		CompletableFuture<File> audioFuture = task.audio() == null ? null : streamDownloader.download(task.audio().getContent(), audioFile, createProgressAdapter(progress -> {
			if (videoSize > 0) aggregator.updateAudio(progress);
			else progress(task.videoId(), progress, (long) (audioSize * (progress / 100.0)), audioSize);
		}));

		(videoFuture != null && audioFuture != null ? CompletableFuture.allOf(videoFuture, audioFuture) : (videoFuture != null ? videoFuture : audioFuture)).thenRun(() -> {
			try {
				if (!tasks.containsKey(task.videoId())) return;
				if (videoFuture != null && audioFuture != null) {
					notify(task.videoId(), ProgressCallback::onMerge);
					File mergedFile = tempFile(task, "_m");
					try {
						MediaMuxer.merge(videoFile, audioFile, mergedFile);
						FileUtils.moveFile(mergedFile, output);
					} finally {
						FileUtils.deleteQuietly(videoFile);
						FileUtils.deleteQuietly(audioFile);
						FileUtils.deleteQuietly(mergedFile);
					}
				} else {
					FileUtils.moveFile(videoFuture != null ? videoFile : audioFile, output);
				}
				complete(task.videoId(), output);
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}).exceptionally(e -> handleError(task, e));
	}

	@Override
	public boolean pause(@NonNull String videoId) {
		Task task = tasks.get(videoId);
		if (task == null) return false;
		if (task.video() != null) streamDownloader.pause(task.video().getContent());
		if (task.audio() != null) streamDownloader.pause(task.audio().getContent());
		return task.video() != null || task.audio() != null;
	}

	@Override
	public boolean resume(@NonNull String videoId) {
		Task task = tasks.get(videoId);
		if (task == null) return false;
		if (task.video() != null) streamDownloader.resume(task.video().getContent());
		if (task.audio() != null) streamDownloader.resume(task.audio().getContent());
		return task.video() != null || task.audio() != null;
	}

	@Override
	public void cancel(@NonNull String videoId) {
		Task task = tasks.remove(videoId);
		try {
			if (task == null) return;
			if (task.video() != null) streamDownloader.cancel(task.video().getContent());
			if (task.audio() != null) streamDownloader.cancel(task.audio().getContent());
			notify(videoId, ProgressCallback::onCancel);
			clean(task);
		} finally {
			clearCallback(videoId);
		}
	}

	private ProgressCallback createProgressAdapter(java.util.function.IntConsumer action) {
		return new ProgressCallback() {
			@Override
			public void onProgress(int progress, long downloadedBytes, long totalBytes) {
				action.accept(progress);
			}

			@Override
			public void onComplete(File file) {
			}

			@Override
			public void onError(Exception e) {
			}

			@Override
			public void onCancel() {
			}
		};
	}

	private Void handleError(Task task, Throwable error) {
		Throwable cause = error instanceof CompletionException ? error.getCause() : error;
		try {
			if (tasks.containsKey(task.videoId())) {
				notify(task.videoId(), callback -> callback.onError(cause instanceof Exception ? (Exception) cause : new Exception(cause)));
				clean(tasks.remove(task.videoId()));
			}
		} finally {
			clearCallback(task.videoId());
		}
		return null;
	}

	private void complete(String videoId, File file) {
		try {
			if (tasks.remove(videoId) != null) notify(videoId, callback -> callback.onComplete(file));
		} finally {
			clearCallback(videoId);
		}
	}

	private void progress(String videoId, int progress, long downloaded, long total) {
		notify(videoId, callback -> callback.onProgress(progress, downloaded, total));
	}

	private void notify(String videoId, CallbackAction action) {
		ProgressCallback callback = callbacks.get(videoId);
		if (callback != null) action.run(callback);
	}

	private void clearCallback(@NonNull String videoId) {
		callbacks.remove(videoId);
	}

	private void clean(Task task) {
		if (task == null) return;
		if (task.video() != null) FileUtils.deleteQuietly(tempFile(task, "_v"));
		if (task.audio() != null) FileUtils.deleteQuietly(tempFile(task, "_a"));
		if (task.video() != null && task.audio() != null) FileUtils.deleteQuietly(tempFile(task, "_m"));
		FileUtils.deleteQuietly(outputFile(task));
	}

	private File tempFile(Task task, String suffix) {
		return new File(context.getCacheDir(), taskFileKey(task) + suffix + ".tmp");
	}

	private File outputFile(@NonNull Task task) {
		if (task.subtitle() != null) {
			return new File(task.desDir(), task.fileName() + "." + task.subtitle().getExtension());
		}
		if (task.thumbnail() != null) {
			return new File(task.desDir(), task.fileName() + ".jpg");
		}
		return new File(task.desDir(), task.fileName() + (task.video() != null ? ".mp4" : ".m4a"));
	}

	private String taskFileKey(@NonNull Task task) {
		return task.videoId().replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	private long contentLength(Stream stream) {
		try {
			return stream.getItagItem().getContentLength();
		} catch (Exception e) {
			return 0;
		}
	}

	interface ThrowingRunnable {
		void run() throws Exception;
	}

	interface CallbackAction {
		void run(ProgressCallback callback);
	}

	interface ProgressUpdateListener {
		void onUpdate(int progress, long downloaded, long total);
	}

	private static class Aggregator {
		final long videoSize, audioSize, totalSize;
		final ProgressUpdateListener listener;
		int videoProgress, audioProgress;

		Aggregator(long videoSize, long audioSize, ProgressUpdateListener listener) {
			this.videoSize = Math.max(videoSize, 1);
			this.audioSize = Math.max(audioSize, 1);
			this.totalSize = this.videoSize + this.audioSize;
			this.listener = listener;
		}

		synchronized void updateVideo(int progress) {
			videoProgress = progress;
			calc();
		}

		synchronized void updateAudio(int progress) {
			audioProgress = progress;
			calc();
		}

		void calc() {
			int totalProgress = (int) ((videoProgress * videoSize + audioProgress * audioSize) / totalSize);
			long downloaded = (long) (videoSize * (videoProgress / 100.0) + audioSize * (audioProgress / 100.0));
			listener.onUpdate(totalProgress, downloaded, totalSize);
		}
	}
}
