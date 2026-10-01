package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneDTO;
import it.bluecube.osrmzonemanager.zone.ZoneService;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import it.bluecube.osrmzonemanager.zone.ZoneUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs the {@code vroom} binary directly for a zone, replacing the per-zone {@code vroom-express}
 * Node process that used to run one HTTP server per zone.
 *
 * <p>One shared binary serves all zones: the routing servers ({@code -a}/{@code -p}) are per-invocation
 * arguments derived from the zone's persisted {@link it.bluecube.osrmzonemanager.zone.ZoneProfile} and
 * its {@code osrm-routed} port, so no per-zone VROOM port or config file is needed.
 *
 * <p>Behavioural contract kept from {@code vroom-express}: payload validation (input error 2, too-large 4),
 * {@code options} overrides, stdout relayed verbatim as the response body, exit code mapped to the HTTP
 * status (0 → 200, 2 → 400, 1/3/other → 500), {@code GET .../vroom/health} returning an empty body.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VroomService {

    private static final byte[] INTERNAL_ERROR_BODY =
            "{\"code\":1,\"error\":\"Internal error\"}".getBytes(StandardCharsets.UTF_8);
    private static final long STDIN_FLUSH_TIMEOUT_MS = 5_000;

    private final OsrmZoneManagerConfig config;
    private final ZoneService zoneService;
    private final VroomCommandBuilder commandBuilder;
    private final VroomOptionsResolver optionsResolver;
    private final VroomRequestValidator requestValidator;
    private final ObjectMapper objectMapper;

    private final Object initLock = new Object();
    private volatile Semaphore concurrencySlots;
    private volatile byte[] healthcheckPayload;

    /**
     * Solves a VROOM request for the given zone by spawning the {@code vroom} binary.
     *
     * @param zoneId zone identifier
     * @param body   raw request body, piped to the binary's stdin
     * @return the response, with the binary's stdout as body
     */
    public ResponseEntity<byte[]> solve(String zoneId, InputStream body) {
        ZoneDTO zone = requireLiveZone(zoneId);
        byte[] payload = readBounded(body, config.getVroomMaxBodyBytes());
        JsonNode json = parse(payload);
        requestValidator.validate(json);
        VroomOptions options = optionsResolver.resolve(json);

        List<String> command = commandBuilder.build(zone.profile(), requireOsrmPort(zone), options);
        log.debug("Zone {}: solving with {}", zoneId, String.join(" ", command));
        return run(zoneId, command, payload, false);
    }

    /**
     * Probes the {@code vroom} binary for a zone with the bundled custom-matrix healthcheck payload
     * (same endpoint contract as {@code vroom-express}: empty body, status only).
     *
     * @param zoneId zone identifier
     * @return 200 with an empty body when the binary solves the healthcheck, 500 otherwise
     */
    public ResponseEntity<byte[]> health(String zoneId) {
        ZoneDTO zone = requireLiveZone(zoneId);
        List<String> command = commandBuilder.buildHealthcheck(zone.profile(), requireOsrmPort(zone));
        return run(zoneId, command, healthcheckPayload(), true);
    }

    private ZoneDTO requireLiveZone(String zoneId) {
        ZoneDTO dto = zoneService.findZone(zoneId);
        ZoneStatus status = dto.status();
        if (status != ZoneStatus.ACTIVE && status != ZoneStatus.DEGRADED) {
            throw new ZoneUnavailableException(zoneId, status != null ? status.name() : "unknown", dto.error());
        }
        zoneService.touch(zoneId);
        return dto;
    }

    private int requireOsrmPort(ZoneDTO zone) {
        Integer port = zone.osrmPort();
        if (port == null || port == 0) {
            throw new IllegalStateException("zone " + zone.zoneId() + " has no OSRM port assigned");
        }
        return port;
    }

    private JsonNode parse(byte[] payload) {
        if (payload.length == 0) {
            throw VroomErrorException.input(VroomRequestValidator.INVALID_INPUT_MESSAGE);
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            if (json == null || !json.isObject()) {
                throw VroomErrorException.input(VroomRequestValidator.INVALID_INPUT_MESSAGE);
            }
            return json;
        } catch (JacksonException _) {
            throw VroomErrorException.input(VroomRequestValidator.INVALID_INPUT_MESSAGE);
        }
    }

    private ResponseEntity<byte[]> run(String zoneId, List<String> command, byte[] stdin, boolean healthOnly) {
        Semaphore gate = concurrencySlots();
        try {
            gate.acquire();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw VroomErrorException.internal("interrupted while waiting for a vroom slot");
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            StringBuilder stderr = new StringBuilder();
            Thread stdoutReader = Thread.ofVirtual().name("vroom-stdout-" + zoneId)
                    .start(() -> transfer(process.getInputStream(), stdout));
            Thread stderrReader = Thread.ofVirtual().name("vroom-stderr-" + zoneId)
                    .start(() -> append(process.getErrorStream(), stderr));

            writeStdin(process, stdin);

            boolean finished = process.waitFor(config.getVroomTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(STDIN_FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                joinQuietly(stdoutReader, stderrReader);
                log.error("Zone {}: vroom timed out after {}ms: {}",
                        zoneId, config.getVroomTimeoutMs(), String.join(" ", command));
                return internalError(healthOnly);
            }

            joinQuietly(stdoutReader, stderrReader);
            int exitCode = process.exitValue();
            String err = stderr.toString().trim();
            if (!err.isEmpty()) {
                log.warn("Zone {}: vroom exit {}: {}", zoneId, exitCode, err);
            }
            if (exitCode != 0) {
                log.warn("Zone {}: vroom exit {} (stdout: {})", zoneId, exitCode, stdout.size());
            }
            return mapExitCode(exitCode, stdout.toByteArray(), healthOnly);
        } catch (IOException e) {
            throw VroomErrorException.internal("failed to run vroom: " + e.getMessage());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw VroomErrorException.internal("interrupted while waiting for vroom");
        } finally {
            gate.release();
        }
    }

    private void writeStdin(Process process, byte[] stdin) throws IOException {
        try (OutputStream os = process.getOutputStream()) {
            os.write(stdin);
            os.flush();
        }
    }

    /**
     * Maps a vroom exit code to the HTTP response, keeping the {@code vroom-express} contract:
     * 0 → 200, 2 → 400, anything else → 500, with stdout relayed as the body.
     *
     * @param exitCode   process exit code
     * @param stdout     captured stdout
     * @param healthOnly when true the body is always empty (health-probe contract)
     * @return the response to send to the caller
     */
    static ResponseEntity<byte[]> mapExitCode(int exitCode, byte[] stdout, boolean healthOnly) {
        if (healthOnly) {
            return exitCode == 0
                    ? ResponseEntity.ok().build()
                    : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        HttpStatus status = switch (exitCode) {
            case 0 -> HttpStatus.OK;
            case 2 -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        byte[] body = stdout != null && stdout.length > 0 ? stdout : INTERNAL_ERROR_BODY;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private ResponseEntity<byte[]> internalError(boolean healthOnly) {
        if (healthOnly) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(INTERNAL_ERROR_BODY);
    }

    private Semaphore concurrencySlots() {
        Semaphore local = concurrencySlots;
        if (local == null) {
            synchronized (initLock) {
                if (concurrencySlots == null) {
                    concurrencySlots = new Semaphore(Math.max(1, config.getVroomMaxConcurrent()), true);
                }
                local = concurrencySlots;
            }
        }
        return local;
    }

    private byte[] healthcheckPayload() {
        byte[] local = healthcheckPayload;
        if (local == null) {
            synchronized (initLock) {
                if (healthcheckPayload == null) {
                    healthcheckPayload = loadHealthcheck();
                }
                local = healthcheckPayload;
            }
        }
        return local;
    }

    private byte[] loadHealthcheck() {
        ClassPathResource resource = new ClassPathResource(config.getVroomHealthcheckResource());
        try (InputStream is = resource.getInputStream()) {
            return is.readAllBytes();
        } catch (IOException e) {
            log.warn("Vroom healthcheck payload {} not readable, using built-in fallback: {}",
                    config.getVroomHealthcheckResource(), e.getMessage());
            return ("{\"vehicles\":[{\"id\":0,\"start_index\":0,\"end_index\":1}],"
                    + "\"jobs\":[{\"id\":1,\"location_index\":0}],\"matrix\":[[0,1],[1,0]]}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private byte[] readBounded(InputStream is, long maxBytes) {
        if (is == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        try {
            while ((read = is.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw VroomErrorException.tooLarge("Request body exceeds max size: " + maxBytes + " bytes");
                }
                buffer.write(chunk, 0, read);
            }
        } catch (IOException e) {
            throw VroomErrorException.input("could not read request body: " + e.getMessage());
        }
        return buffer.toByteArray();
    }

    private void transfer(InputStream is, ByteArrayOutputStream sink) {
        try (is; sink) {
            is.transferTo(sink);
        } catch (IOException e) {
            log.debug("Failed reading vroom stdout: {}", e.getMessage());
        }
    }

    private void append(InputStream is, StringBuilder sink) {
        try (is) {
            sink.append(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Failed reading vroom stderr: {}", e.getMessage());
        }
    }

    private void joinQuietly(Thread... threads) {
        for (Thread thread : threads) {
            try {
                thread.join(STDIN_FLUSH_TIMEOUT_MS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
