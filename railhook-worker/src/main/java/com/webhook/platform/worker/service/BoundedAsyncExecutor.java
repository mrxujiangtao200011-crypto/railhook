package com.webhook.platform.worker.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class BoundedAsyncExecutor {

    private final String name;
    private final ExecutorService executor;
    private final Semaphore semaphore;
    private final long shutdownTimeoutSeconds;
    private final AtomicInteger inFlight = new AtomicInteger(0);

    public BoundedAsyncExecutor(String name, int poolSize, long shutdownTimeoutSeconds, MeterRegistry meterRegistry) {
        this.name = name;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.semaphore = new Semaphore(poolSize);
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r);
            t.setName(name + "-worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        String metricPrefix = name.replace("-", "_");
        Gauge.builder(metricPrefix + "_in_flight", inFlight, AtomicInteger::doubleValue)
                .description("Number of in-flight tasks in " + name + " executor")
                .register(meterRegistry);
        Gauge.builder(metricPrefix + "_available_permits", semaphore, s -> (double) s.availablePermits())
                .description("Available permits in " + name + " executor")
                .register(meterRegistry);
    }

    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }

    public int getInFlightCount() {
        return inFlight.get();
    }

    public void submit(Runnable task) {
        semaphore.acquireUninterruptibly();
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("{}: task failed: {}", name, e.getMessage(), e);
                } finally {
                    inFlight.decrementAndGet();
                    semaphore.release();
                }
            });
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            semaphore.release();
            throw e;
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down {} executor, waiting for {} in-flight tasks", name, inFlight.get());
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("{} executor did not terminate in {}s; {} claims are left to time out",
                        name, shutdownTimeoutSeconds, inFlight.get());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
