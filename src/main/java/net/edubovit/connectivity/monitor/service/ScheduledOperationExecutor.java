package net.edubovit.connectivity.monitor.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import net.edubovit.connectivity.monitor.config.ConnectivityMonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

@Service
public class ScheduledOperationExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScheduledOperationExecutor.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final Semaphore semaphore;

    public ScheduledOperationExecutor(ConnectivityMonitorProperties properties) {
        this.semaphore = new Semaphore(concurrencyLimit(properties));
    }

    public Future<?> submit(String description, Runnable runnable) {
        return executor.submit(() -> runWithPermit(description, runnable));
    }

    private void runWithPermit(String description, Runnable runnable) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            runnable.run();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Scheduled operation interrupted before execution: {}", description);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private int concurrencyLimit(ConnectivityMonitorProperties properties) {
        int configuredConcurrency = properties.getConcurrency();
        if (configuredConcurrency < 1) {
            log.warn("Invalid connectivity concurrency configured: {}. Using 1 instead", configuredConcurrency);
            return 1;
        }
        return configuredConcurrency;
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
