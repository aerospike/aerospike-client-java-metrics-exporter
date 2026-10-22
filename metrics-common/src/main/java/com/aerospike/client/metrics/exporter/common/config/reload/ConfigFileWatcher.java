/*
 * Copyright 2012-2026 Aerospike, Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.aerospike.client.metrics.exporter.common.config.reload;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls a fixed set of files and reports the ones whose content changed.
 *
 * <p>Polling is used rather than {@link java.nio.file.WatchService} because a
 * watch service reports directory entries rather than files. A Kubernetes
 * secret rotation names {@code ..data} rather than the certificate, macOS
 * falls back to a polling implementation anyway, and network filesystems
 * often deliver no events at all. A watch service would also need a thread
 * blocked on {@code take()}, so it saves nothing here.</p>
 *
 * <p>A change is reported only once it has been stable for a further interval.
 * That stops a file caught halfway through a write from being acted on, which
 * matters when related certificates and keys are rotated together. Worst-case
 * detection latency is two intervals.</p>
 *
 * <p>Every watcher in the process shares one daemon thread, since exporters are
 * shared across client libraries and a per-exporter thread would multiply for
 * no benefit. The thread is created with the first watcher to start and shut
 * down with the last one to close. Listeners run on this shared thread and
 * must return promptly; a future backend reload handler must dispatch blocking
 * transport work to an appropriate executor.</p>
 */
public final class ConfigFileWatcher implements Closeable {
    /**
     * Receives changed files on the shared watcher thread. Never called with
     * an empty set; implementations must not perform blocking reload work.
     */
    public interface Listener {
        void onFilesChanged(Set<Path> changed);
    }

    private static final Logger LOGGER = Logger.getLogger(ConfigFileWatcher.class.getName());
    private static final String THREAD_NAME = "aerospike-metrics-config-watcher";

    private static final Object LIFECYCLE = new Object();
    private static ScheduledExecutorService sharedScheduler;
    private static int running;

    private final String name;
    private final List<Path> files;
    private final Duration interval;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Path> unreadable = new LinkedHashSet<Path>();

    /** Held across a poll so close() can wait for one already in progress. */
    private final Object pollLock = new Object();

    private ScheduledFuture<?> task;
    private Map<Path, FileFingerprint> applied;
    private Map<Path, FileFingerprint> pending;

    public ConfigFileWatcher(
            String name,
            Collection<Path> files,
            Duration interval,
            Listener listener
    ) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files must not be empty");
        }
        if (interval == null || interval.toMillis() < 1L) {
            throw new IllegalArgumentException("interval must be at least 1ms");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        this.name = name;
        this.files = Collections.unmodifiableList(
            new ArrayList<Path>(new LinkedHashSet<Path>(files))
        );
        this.interval = interval;
        this.listener = listener;
        this.applied = fingerprintAll();
    }

    public void start() {
        synchronized (this) {
            if (closed.get() || task != null) {
                return;
            }
            long millis = interval.toMillis();
            task = acquireScheduler().scheduleWithFixedDelay(
                this::safePoll,
                millis,
                millis,
                TimeUnit.MILLISECONDS
            );
        }
        LOGGER.log(
            Level.INFO,
            "{0}: watching {1} configuration file(s) every {2}",
            new Object[] {name, files.size(), interval}
        );
    }

    public List<Path> watchedFiles() {
        return files;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        ScheduledFuture<?> cancelled;
        synchronized (this) {
            cancelled = task;
            task = null;
        }
        if (cancelled == null) {
            return;
        }

        // cancel(false) leaves a poll already running alone; taking the poll lock
        // then waits for it rather than interrupting a file read.
        cancelled.cancel(false);
        synchronized (pollLock) {
            releaseScheduler();
        }
    }

    /**
     * Wraps {@link #pollOnce()} so nothing escapes to the scheduler, which
     * silently cancels all future runs when a task throws.
     */
    void safePoll() {
        synchronized (pollLock) {
            try {
                pollOnce();
            }
            catch (Throwable t) {
                LOGGER.log(
                    Level.WARNING,
                    name + ": configuration check failed; retrying next interval",
                    t
                );
            }
        }
    }

    /** Runs one check. Visible so tests drive it directly instead of waiting on the clock. */
    void pollOnce() {
        if (closed.get()) {
            return;
        }

        Map<Path, FileFingerprint> current = fingerprintAll();
        if (current.equals(applied)) {
            pending = null;
            return;
        }
        if (!current.equals(pending)) {
            // Seen for the first time. Wait one more interval for the writer to finish.
            pending = current;
            return;
        }

        // Recorded before the listener runs, and deliberately not rolled back when
        // it fails. The same bytes would fail the same way, so a bad file is
        // attempted once and then left alone until it changes again.
        Set<Path> changed = difference(applied, current);
        applied = current;
        pending = null;
        if (!changed.isEmpty()) {
            listener.onFilesChanged(changed);
        }
    }

    /** Number of started, unclosed watchers. Visible for testing the shared thread. */
    static int running() {
        synchronized (LIFECYCLE) {
            return running;
        }
    }

    private static ScheduledExecutorService acquireScheduler() {
        synchronized (LIFECYCLE) {
            if (sharedScheduler == null) {
                sharedScheduler = Executors.newSingleThreadScheduledExecutor(
                    new WatcherThreadFactory()
                );
            }
            running++;
            return sharedScheduler;
        }
    }

    private static void releaseScheduler() {
        synchronized (LIFECYCLE) {
            if (--running > 0 || sharedScheduler == null) {
                return;
            }
            ScheduledExecutorService stopping = sharedScheduler;
            sharedScheduler = null;
            stopping.shutdown();
            // A listener may close its own watcher on this scheduler thread.
            // Waiting for termination there would make the task wait for itself.
            if (THREAD_NAME.equals(Thread.currentThread().getName())) {
                return;
            }
            try {
                if (!stopping.awaitTermination(5L, TimeUnit.SECONDS)) {
                    stopping.shutdownNow();
                }
            }
            catch (InterruptedException e) {
                stopping.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private Map<Path, FileFingerprint> fingerprintAll() {
        Map<Path, FileFingerprint> current = new LinkedHashMap<Path, FileFingerprint>();
        for (Path file : files) {
            try {
                current.put(file, FileFingerprint.of(file));
                if (unreadable.remove(file)) {
                    LOGGER.log(
                        Level.INFO,
                        "{0}: {1} is readable again",
                        new Object[] {name, file}
                    );
                }
            }
            catch (IOException | RuntimeException e) {
                // A rotation that deletes and recreates leaves the file missing for an
                // instant. Carrying the last known state forward keeps that gap from
                // looking like a change, and keeps the warning to once per outage.
                FileFingerprint last = applied == null ? null : applied.get(file);
                if (last != null) {
                    current.put(file, last);
                }
                if (unreadable.add(file)) {
                    LOGGER.log(
                        Level.WARNING,
                        "{0}: cannot read {1} ({2}); keeping the last known state",
                        new Object[] {name, file, e.getMessage()}
                    );
                }
            }
        }
        return current;
    }

    private static Set<Path> difference(
            Map<Path, FileFingerprint> before,
            Map<Path, FileFingerprint> after
    ) {
        Set<Path> changed = new LinkedHashSet<Path>();
        for (Map.Entry<Path, FileFingerprint> entry : after.entrySet()) {
            FileFingerprint previous = before.get(entry.getKey());
            if (previous == null || !previous.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        for (Path path : before.keySet()) {
            if (!after.containsKey(path)) {
                changed.add(path);
            }
        }
        return changed;
    }

    private static final class WatcherThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, THREAD_NAME);
            // Daemon so the watcher never keeps a JVM alive on its own.
            thread.setDaemon(true);
            return thread;
        }
    }
}
