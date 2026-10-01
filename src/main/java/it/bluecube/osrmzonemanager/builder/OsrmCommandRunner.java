package it.bluecube.osrmzonemanager.builder;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs external build subprocesses ({@code osmium}, {@code osrm-extract/partition/customize},
 * {@code reduce.py}) and resolves the Lua profile script for a routing profile.
 *
 * <p>Extracted from {@link BuildPipelineService} so that the whole-map (global) build in
 * {@code it.bluecube.osrmzonemanager.global} can reuse the same execution and logging behaviour
 * while keeping its own, much larger, timeout.
 *
 * <p>stdout/stderr are merged and drained continuously so the child cannot dead-lock on a full
 * pipe buffer; only the last {@value #MAX_OUTPUT_LINES} lines are retained for error reporting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OsrmCommandRunner {

    private static final int MAX_OUTPUT_LINES = 500;

    private final OsrmZoneManagerConfig config;

    /**
     * Resolves the Lua profile script for a routing profile.
     *
     * @param profile routing profile ({@code null} is treated as {@link ZoneProfile#CAR})
     * @return the absolute path to the Lua script
     */
    public String profileLuaPath(ZoneProfile profile) {
        return switch (profile == null ? ZoneProfile.CAR : profile) {
            case CAR -> config.getCarLua();
            case BUS -> config.getBusLua();
        };
    }

    /**
     * Runs an external subprocess with a timeout, merging stderr into stdout.
     *
     * @param command        command and arguments
     * @param cwd            working directory (or {@code null} for the current directory)
     * @param timeoutSeconds wall-clock budget before the process is killed
     * @throws BuildException on timeout or non-zero exit code
     * @throws IOException    on I/O failure while starting the process
     */
    public void run(List<String> command, File cwd, int timeoutSeconds) throws IOException {
        log.info("Starting subprocess: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) {
            pb.directory(cwd);
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();

        Deque<String> output = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.addLast(line);
                if (output.size() > MAX_OUTPUT_LINES) {
                    output.removeFirst();
                }
                log.debug("{}", line);
            }
        }

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildException("subprocess wait interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new BuildException("subprocess timed out after "
                    + timeoutSeconds + "s: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            List<String> tailList = new ArrayList<>(output);
            String tail = String.join("\n", tailList.subList(
                    Math.max(0, tailList.size() - 20), tailList.size()));
            throw new BuildException("subprocess failed (rc=" + process.exitValue()
                    + "): " + String.join(" ", command) + " - " + tail);
        }
        if (!output.isEmpty()) {
            log.info("{}: {}", String.join(" ", command), output.getLast());
        }
    }
}
