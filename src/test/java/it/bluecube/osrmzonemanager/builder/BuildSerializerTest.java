package it.bluecube.osrmzonemanager.builder;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class BuildSerializerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static Thread zoneBuild(BuildSerializer serializer, String id, List<String> events,
                                    CountDownLatch inside, CountDownLatch mayExit) {
        return new Thread(() -> run(serializer, id, events, inside, mayExit, true), id);
    }

    private static Thread globalBuild(BuildSerializer serializer, String id, List<String> events) {
        return new Thread(() -> run(serializer, id, events, new CountDownLatch(0), new CountDownLatch(0), false), id);
    }

    private static void run(BuildSerializer serializer, String id, List<String> events,
                            CountDownLatch inside, CountDownLatch mayExit, boolean zone) {
        try {
            if (zone) {
                serializer.acquireZone();
            } else {
                serializer.acquireGlobal();
            }
            try {
                events.add(id + ":start");
                inside.countDown();
                mayExit.await();
                events.add(id + ":end");
            } finally {
                serializer.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitLatch(CountDownLatch latch) throws InterruptedException {
        return latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void awaitQueue(BuildSerializer serializer, int zoneQueued, int globalQueued) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (serializer.queuedZoneBuilds() == zoneQueued && serializer.queuedGlobalBuilds() == globalQueued) {
                return;
            }
            Thread.onSpinWait();
        }
        Assertions.fail("build queue never reached zone=%d global=%d".formatted(zoneQueued, globalQueued));
    }

    private static void join(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(TIMEOUT.toMillis());
            Assertions.assertThat(thread.isAlive()).isFalse();
        }
    }

    @Test
    void shouldRunOneBuildAtATime() throws Exception {
        BuildSerializer serializer = new BuildSerializer();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch firstMayExit = new CountDownLatch(1);

        Thread zone1 = zoneBuild(serializer, "zone-1", events, firstInside, firstMayExit);
        zone1.start();
        Assertions.assertThat(awaitLatch(firstInside)).isTrue();

        Thread zone2 = zoneBuild(serializer, "zone-2", events, new CountDownLatch(0), new CountDownLatch(0));
        zone2.start();
        awaitQueue(serializer, 1, 0);

        Assertions.assertThat(events).containsExactly("zone-1:start");

        firstMayExit.countDown();
        join(zone1, zone2);

        Assertions.assertThat(events).containsExactly(
                "zone-1:start", "zone-1:end", "zone-2:start", "zone-2:end");
    }

    @Test
    void shouldNotStartGlobalBuildWhileZoneBuildRuns() throws Exception {
        BuildSerializer serializer = new BuildSerializer();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch firstMayExit = new CountDownLatch(1);

        Thread zone = zoneBuild(serializer, "zone-1", events, firstInside, firstMayExit);
        zone.start();
        Assertions.assertThat(awaitLatch(firstInside)).isTrue();

        Thread global = globalBuild(serializer, "global-1", events);
        global.start();
        awaitQueue(serializer, 0, 1);

        Assertions.assertThat(events).containsExactly("zone-1:start");

        firstMayExit.countDown();
        join(zone, global);

        Assertions.assertThat(events).containsExactly(
                "zone-1:start", "zone-1:end", "global-1:start", "global-1:end");
    }

    @Test
    void shouldLetQueuedZoneBuildOvertakeQueuedGlobalBuild() throws Exception {
        BuildSerializer serializer = new BuildSerializer();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch firstMayExit = new CountDownLatch(1);

        Thread running = zoneBuild(serializer, "zone-1", events, firstInside, firstMayExit);
        running.start();
        Assertions.assertThat(awaitLatch(firstInside)).isTrue();

        Thread global = globalBuild(serializer, "global-1", events);
        global.start();
        awaitQueue(serializer, 0, 1);

        Thread zone2 = zoneBuild(serializer, "zone-2", events, new CountDownLatch(0), new CountDownLatch(0));
        zone2.start();
        awaitQueue(serializer, 1, 1);

        firstMayExit.countDown();
        join(running, zone2, global);

        Assertions.assertThat(events).containsExactly(
                "zone-1:start", "zone-1:end",
                "zone-2:start", "zone-2:end",
                "global-1:start", "global-1:end");
    }

    @Test
    void shouldReleaseSlotWhenGlobalBuildFinishes() throws Exception {
        BuildSerializer serializer = new BuildSerializer();

        serializer.acquireGlobal();
        Assertions.assertThat(serializer.isBusy()).isTrue();
        serializer.release();

        Assertions.assertThat(serializer.isBusy()).isFalse();
        Assertions.assertThat(serializer.queuedZoneBuilds()).isZero();
        Assertions.assertThat(serializer.queuedGlobalBuilds()).isZero();
    }
}
