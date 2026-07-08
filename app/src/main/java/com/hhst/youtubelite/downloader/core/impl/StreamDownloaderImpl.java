package com.hhst.youtubelite.downloader.core.impl;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.hhst.youtubelite.downloader.core.StreamDownloader;
import com.tencent.mmkv.MMKV;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.AllArgsConstructor;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Streams a file by chunk and keeps resume state in MMKV.
 */
@Singleton
public class StreamDownloaderImpl implements StreamDownloader {
	private static final String TAG = "StreamDownloader";
	private final OkHttpClient client;
	private final MMKV mmkv;
	private final ThreadPoolExecutor executor;
	/**
	 * Runs download coordinators ({@link #runTask}). Distinct from
	 * {@link #executor} because {@code runTask} blocks on
	 * {@link CompletableFuture#join} waiting for chunk tasks submitted to
	 * {@code executor}; sharing the same pool would deadlock once all worker
	 * threads are occupied by coordinators with no thread left to run chunks.
	 */
	private final ExecutorService coordinatorExecutor =
			Executors.newCachedThreadPool(StreamDownloaderImpl::namedDaemonThread);
	private final Map<String, TaskContext> tasks = new ConcurrentHashMap<>();

	@Inject
	public StreamDownloaderImpl(OkHttpClient client, MMKV mmkv) {
		this.client = client.newBuilder()
						.cache(null)
						.dispatcher(createDispatcher())
						.callTimeout(0L, TimeUnit.MILLISECONDS)
						.connectTimeout(20L, TimeUnit.SECONDS)
						.writeTimeout(30L, TimeUnit.SECONDS)
						.readTimeout(60L, TimeUnit.SECONDS)
						.build();
		this.mmkv = mmkv;
		this.executor = new ThreadPoolExecutor(8, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> new Thread(r, "dl-node"));
		this.executor.allowCoreThreadTimeOut(true);
	}

	private static Thread namedDaemonThread(Runnable r) {
		Thread t = new Thread(r, "dl-coord");
		t.setDaemon(true);
		return t;
	}

	private static Dispatcher createDispatcher() {
		Dispatcher dispatcher = new Dispatcher();
		dispatcher.setMaxRequests(24);
		dispatcher.setMaxRequestsPerHost(12);
		return dispatcher;
	}

	private static long chunkLength(int idx, int totalChunks, long partSize, long totalLen) {
		long start = idx * partSize;
		long end = (idx == totalChunks - 1 && totalLen > 0) ? totalLen - 1 : (start + partSize - 1);
		if (totalLen <= 0 || end < start) return 0;
		return end - start + 1;
	}

	private static void maybeReportProgress(@NonNull TaskContext task, long totalLen) {
		if (task.callback == null || totalLen <= 0) return;
		long downloaded = Math.min(totalLen, Math.max(0, task.downloadedBytes.get()));
		int progress = (int) Math.min(99, (downloaded * 100) / totalLen);
		synchronized (task.progressLock) {
			int prev;
			do {
				prev = task.lastProgress.get();
				if (progress <= prev) return;
			} while (!task.lastProgress.compareAndSet(prev, progress));
			task.callback.onProgress(progress, downloaded, totalLen);
		}
	}

	@Override
	public CompletableFuture<File> download(@NonNull String url, @NonNull File out, @Nullable ProgressCallback callback) {
		CompletableFuture<File> future = new CompletableFuture<>();
		TaskContext task = new TaskContext(
						url,
						out,
						"dl_" + md5(url),
						future,
						callback,
						new AtomicBoolean(),
						new AtomicBoolean(),
						new AtomicInteger(),
						new AtomicLong(),
						new AtomicInteger(-1));
		tasks.put(url, task);
		coordinatorExecutor.execute(() -> runTask(task));
		return future;
	}

	private void runTask(TaskContext task) {
		RandomAccessFile raf = null;
		try {
			Metadata meta = fetchMetadata(task.url);
			ChunkPlan plan = computeChunkPlan(meta);
			BitSet bits = restoreResumeState(task, meta, plan);
			raf = openOutputFile(task.out, meta.total());
			if (task.done.get() < plan.chunks()) {
				runChunkDownloads(task, plan, meta, raf, bits);
			}
			completeTask(task);
		} catch (Exception e) {
			if (!task.isInactive()) {
				tasks.remove(task.url);
				task.future.completeExceptionally(e);
				if (task.callback != null) {
					task.callback.onError(unwrapDownloadError(e));
				}
			}
		} finally {
			closeQuietly(raf);
		}
	}

	private Metadata fetchMetadata(@NonNull String url) throws IOException {
		try (Response head = client.newCall(new Request.Builder().url(url).head().build()).execute()) {
			if (!head.isSuccessful()) throw new IOException("HEAD " + head.code());
			long total = Long.parseLong(head.header("Content-Length", "-1"));
			boolean range = head.code() == 206 || "bytes".equalsIgnoreCase(head.header("Accept-Ranges"));
			return new Metadata(total, range);
		}
	}

	private ChunkPlan computeChunkPlan(@NonNull Metadata meta) {
		int chunks;
		if (meta.total() <= 0 || !meta.rangeSupported()) {
			chunks = 1;
		} else {
			int candidate = (int) Math.min(128, Math.max(4, meta.total() / (512 * 1024)));
			chunks = (meta.total() / Math.max(candidate, 1)) > 0 ? candidate : 1;
		}
		long part = meta.total() > 0 ? meta.total() / chunks : meta.total();
		return new ChunkPlan(chunks, part);
	}

	private BitSet restoreResumeState(@NonNull TaskContext task, @NonNull Metadata meta, @NonNull ChunkPlan plan) {
		byte[] saved = mmkv.decodeBytes(task.key);
		BitSet bits = (meta.rangeSupported() && saved != null) ? BitSet.valueOf(saved) : new BitSet();
		task.done.set(bits.cardinality());
		if (meta.total() > 0) {
			long initialDownloaded = IntStream.range(0, plan.chunks())
					.filter(bits::get)
					.mapToLong(i -> chunkLength(i, plan.chunks(), plan.partSize(), meta.total()))
					.sum();
			task.downloadedBytes.set(initialDownloaded);
			maybeReportProgress(task, meta.total());
		}
		return bits;
	}

	@NonNull
	private RandomAccessFile openOutputFile(@NonNull File out, long total) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(out, "rw");
		raf.setLength(total > 0 ? total : 0);
		return raf;
	}

	private void runChunkDownloads(@NonNull TaskContext task, @NonNull ChunkPlan plan, @NonNull Metadata meta,
	                               @NonNull RandomAccessFile raf, @NonNull BitSet bits) {
		CompletableFuture.allOf(IntStream.range(0, plan.chunks())
				.filter(i -> !bits.get(i))
				.mapToObj(i -> CompletableFuture.runAsync(
						() -> downloadChunk(task, i, plan, meta, raf, bits),
						executor))
				.toArray(CompletableFuture[]::new)).join();
	}

	private void completeTask(@NonNull TaskContext task) {
		mmkv.removeValueForKey(task.key);
		tasks.remove(task.url);
		task.future.complete(task.out);
		if (task.callback != null) task.callback.onComplete(task.out);
	}

	private void closeQuietly(@Nullable RandomAccessFile raf) {
		if (raf == null) return;
		try {
			raf.close();
		} catch (IOException e) {
			Log.w(TAG, "runTask: failed to close random access file", e);
		}
	}

	private static Exception unwrapDownloadError(Exception e) {
		Throwable cur = e;
		while (cur instanceof CompletionException || cur instanceof ChunkDownloadException) {
			Throwable cause = cur.getCause();
			if (!(cause instanceof Exception)) break;
			cur = cause;
		}
		return (Exception) cur;
	}

	private void downloadChunk(TaskContext task, int idx, ChunkPlan plan, Metadata meta, RandomAccessFile raf, BitSet bits) {
		if (task.isInactive()) return;
		long start = idx * plan.partSize();
		long end = (idx == plan.chunks() - 1 && meta.total() > 0) ? meta.total() - 1 : (start + plan.partSize() - 1);
		String range = meta.rangeSupported() && meta.total() > 0 ? "bytes=" + start + "-" + end : null;

		Request.Builder rb = new Request.Builder().url(task.url);
		if (range != null) rb.header("Range", range);

		try (Response resp = client.newCall(rb.build()).execute()) {
			if (!resp.isSuccessful()) throw new IOException("GET " + resp.code());
			try (InputStream is = resp.body().byteStream()) {
				byte[] buf = new byte[8192];
				int read;
				long offset = start;
				while ((read = is.read(buf)) != -1) {
					if (task.isInactive()) throw new IOException("Stop");
					synchronized (task.lock) {
						raf.seek(offset);
						raf.write(buf, 0, read);
					}
					if (meta.total() > 0) {
						task.downloadedBytes.addAndGet(read);
						maybeReportProgress(task, meta.total());
					}
					offset += read;
				}
				if (range != null) synchronized (task.lock) {
					bits.set(idx);
					mmkv.encode(task.key, bits.toByteArray());
				}
			}
		} catch (Exception e) {
			throw new ChunkDownloadException(e);
		}
	}

	@Override
	public void pause(@NonNull String url) {
		Optional.ofNullable(tasks.get(url)).ifPresent(t -> t.paused.set(true));
	}

	@Override
	public void cancel(@NonNull String url) {
		TaskContext t = tasks.remove(url);
		if (t != null) {
			t.cancelled.set(true);
			t.future.cancel(true);
			mmkv.removeValueForKey(t.key);
			if (t.callback != null) t.callback.onCancel();
		}
	}

	@Override
	public void resume(@NonNull String url) {
		TaskContext t = tasks.get(url);
		if (t != null && t.paused.compareAndSet(true, false)) coordinatorExecutor.execute(() -> runTask(t));
	}

	@Override
	public synchronized void setMaxThreadCount(int count) {
		int targetCount = Math.max(1, count);
		Dispatcher dispatcher = client.dispatcher();
		dispatcher.setMaxRequests(targetCount);
		dispatcher.setMaxRequestsPerHost(targetCount);
		if (targetCount > executor.getMaximumPoolSize()) {
			executor.setMaximumPoolSize(targetCount);
			executor.setCorePoolSize(targetCount);
		} else {
			executor.setCorePoolSize(targetCount);
			executor.setMaximumPoolSize(targetCount);
		}
	}

	private String md5(String s) {
		try {
			byte[] b = MessageDigest.getInstance("MD5").digest(s.getBytes());
			StringBuilder sb = new StringBuilder();
			for (byte v : b) sb.append(String.format("%02x", v));
			return sb.toString();
		} catch (Exception e) {
			return String.valueOf(s.hashCode());
		}
	}

	private record Metadata(long total, boolean rangeSupported) {
	}

	private record ChunkPlan(int chunks, long partSize) {
	}

	private static final class ChunkDownloadException extends RuntimeException {
		ChunkDownloadException(Exception cause) { super(cause); }

		Exception unwrap() { return (Exception) getCause(); }
	}

	@AllArgsConstructor
	private static class TaskContext {
		final String url;
		final File out;
		final String key;
		final CompletableFuture<File> future;
		final ProgressCallback callback;
		final Object lock = new Object();
		final Object progressLock = new Object();
		final AtomicBoolean paused;
		final AtomicBoolean cancelled;
		final AtomicInteger done;
		final AtomicLong downloadedBytes;
		final AtomicInteger lastProgress;

		boolean isInactive() {
			return paused.get() || cancelled.get() || future.isCancelled();
		}
	}
}
