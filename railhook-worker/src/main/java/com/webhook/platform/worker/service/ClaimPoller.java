package com.webhook.platform.worker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Claims only as many rows as the executor has free threads, so a claimed row never waits. */
@Slf4j
public class ClaimPoller<T> implements SmartLifecycle {

    private static final long BUSY_MILLIS = 20;
    private static final long ERROR_BACKOFF_MILLIS = 2000;

    private final String name;
    private final BoundedAsyncExecutor executor;
    private final IntFunction<List<T>> claim;
    private final Consumer<T> attempt;
    private final long idleMillis;
    private final boolean autoStartup;

    private volatile boolean running;
    private Thread thread;

    public ClaimPoller(String name, BoundedAsyncExecutor executor, IntFunction<List<T>> claim,
            Consumer<T> attempt, long idleMillis, boolean autoStartup) {
        this.name = name;
        this.executor = executor;
        this.claim = claim;
        this.attempt = attempt;
        this.idleMillis = idleMillis;
        this.autoStartup = autoStartup;
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::loop, name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(ERROR_BACKOFF_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    private void loop() {
        while (running) {
            try {
                int free = executor.getAvailablePermits();
                if (free == 0) {
                    Thread.sleep(BUSY_MILLIS);
                    continue;
                }
                List<T> batch = claim.apply(free);
                for (T item : batch) {
                    executor.submit(() -> attempt.accept(item));
                }
                if (batch.isEmpty()) {
                    Thread.sleep(idleMillis);
                } else if (batch.size() < free) {
                    Thread.sleep(BUSY_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("{}: claiming failed: {}", name, e.getMessage(), e);
                try {
                    Thread.sleep(ERROR_BACKOFF_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
