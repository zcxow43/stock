package com.stock.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, in-memory, whether a batch job of a given jobType is currently running.
 * Guards against a second backfill for the same jobType being started while one
 * is still in progress (spec: 409 JOB_ALREADY_RUNNING).
 */
@Component
public class JobRunningRegistry {

    private final ConcurrentHashMap<String, Boolean> running = new ConcurrentHashMap<>();

    /** Atomically marks jobType as running iff it wasn't already. Returns true if this call won the race. */
    public synchronized boolean tryStart(String jobType) {
        if (Boolean.TRUE.equals(running.get(jobType))) {
            return false;
        }
        running.put(jobType, Boolean.TRUE);
        return true;
    }

    public synchronized void finish(String jobType) {
        running.remove(jobType);
    }

    public synchronized boolean isRunning(String jobType) {
        return Boolean.TRUE.equals(running.get(jobType));
    }
}
