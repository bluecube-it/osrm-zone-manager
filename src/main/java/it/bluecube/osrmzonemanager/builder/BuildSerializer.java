package it.bluecube.osrmzonemanager.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Global, single-slot serializer for every memory-hungry OSRM preprocessing build.
 *
 * <p>{@code osrm-extract} alone peaks at roughly 6–7× the size of its input PBF (≈15 GB for the
 * whole-Italy extract), so running two builds at the same time on a 16 GB host is an instant
 * out-of-memory kill — and the OOM killer is free to pick the database or the gateway instead of the
 * build. Every build therefore goes through this one slot: zone builds ({@link BuildPipelineService})
 * and whole-map global builds ({@code GlobalOsrmService}) are mutually exclusive, always, whatever
 * their size.
 *
 * <p>Zone builds have priority over global builds: they are user-facing, while a whole-map rebuild is
 * a background job that can legitimately wait hours behind a zone. A global build acquires the slot
 * only when no zone build is running <em>and</em> none is queued, so a busy zone workload cannot be
 * starved by a process-one-profile-then-the-other whole-map job. Among builds of the same kind the
 * condition queue is FIFO.
 *
 * <p>Usage is always {@code acquireX() … try { … } finally { release(); }} on the same thread.
 */
@Slf4j
@Component
public class BuildSerializer {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition zoneAvailable = lock.newCondition();
    private final Condition globalAvailable = lock.newCondition();

    private boolean busy;
    private int waitingZoneBuilds;
    private int waitingGlobalBuilds;

    /**
     * Waits for the single build slot, for a zone build.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void acquireZone() throws InterruptedException {
        int queuedZone;
        int queuedGlobal;
        lock.lockInterruptibly();
        try {
            waitingZoneBuilds++;
            try {
                while (busy) {
                    zoneAvailable.await();
                }
            } finally {
                waitingZoneBuilds--;
            }
            busy = true;
            queuedZone = waitingZoneBuilds;
            queuedGlobal = waitingGlobalBuilds;
        } finally {
            lock.unlock();
        }
        log.info("Build slot acquired by a zone build ({} zone / {} whole-map build(s) queued)",
                queuedZone, queuedGlobal);
    }

    /**
     * Waits for the single build slot, for a whole-map global build. Yields to any zone build that is
     * running or queued.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void acquireGlobal() throws InterruptedException {
        int queuedZone;
        int queuedGlobal;
        lock.lockInterruptibly();
        try {
            waitingGlobalBuilds++;
            try {
                while (busy || waitingZoneBuilds > 0) {
                    globalAvailable.await();
                }
            } finally {
                waitingGlobalBuilds--;
            }
            busy = true;
            queuedZone = waitingZoneBuilds;
            queuedGlobal = waitingGlobalBuilds;
        } finally {
            lock.unlock();
        }
        log.info("Build slot acquired by a whole-map build ({} zone / {} whole-map build(s) queued)",
                queuedZone, queuedGlobal);
    }

    /**
     * Frees the single build slot and wakes every waiter; a woken global build re-checks by itself
     * whether a zone build is still queued ahead of it.
     */
    public void release() {
        lock.lock();
        try {
            busy = false;
            zoneAvailable.signalAll();
            globalAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return {@code true} when a build currently holds the slot; exposed for tests and diagnostics
     */
    public boolean isBusy() {
        lock.lock();
        try {
            return busy;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return number of zone builds currently waiting for the slot; package-private for tests
     */
    int queuedZoneBuilds() {
        lock.lock();
        try {
            return waitingZoneBuilds;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return number of whole-map builds currently waiting for the slot; package-private for tests
     */
    int queuedGlobalBuilds() {
        lock.lock();
        try {
            return waitingGlobalBuilds;
        } finally {
            lock.unlock();
        }
    }
}
