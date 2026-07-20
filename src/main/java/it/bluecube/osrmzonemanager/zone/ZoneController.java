package it.bluecube.osrmzonemanager.zone;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for zone CRUD — delegates to {@link ZoneService}.
 * HTTP status mapping: 201 Created for new zones, 200 OK for reuse.
 */
@Slf4j
@RestController
@RequestMapping("/zones")
@RequiredArgsConstructor
public class ZoneController {

    private static final String STATUS_DELETED = "deleted";

    private final ZoneService zoneService;

    /**
     * @param request zone creation input (polygon, optional lineStrings)
     * @return the zone DTO — 201 for newly created, 200 for reused
     */
    @PostMapping
    public ResponseEntity<ZoneDTO> createZone(@RequestBody ZoneInputDTO request) {
        ZoneDTO zone = zoneService.createOrReuseZone(request.polygon(), request.lineStrings());
        boolean reused = ZoneService.ZONE_REUSE_MESSAGE.equals(zone.message());

        ZoneDTO dto = zone.withProcess(null);
        return ResponseEntity.status(reused ? HttpStatus.OK : HttpStatus.CREATED).body(dto);
    }

    /**
     * @return list of all zones sorted by last access (descending)
     */
    @GetMapping
    public ResponseEntity<List<ZoneDTO>> listZones() {
        return ResponseEntity.ok(zoneService.findAllZones());
    }

    /**
     * @param zoneId zone identifier
     * @return zone details with process status
     */
    @GetMapping("/{zoneId}")
    public ResponseEntity<ZoneDTO> getZone(@PathVariable String zoneId) {
        ZoneDTO zone = zoneService.findZone(zoneId);
        String process = zoneService.isZoneRunning(zoneId) ? "running" : null;
        ZoneDTO dto = zone.withProcess(process);
        return ResponseEntity.ok(dto);
    }

    /**
     * @param zoneId zone identifier
     * @return confirmation with zoneId and deleted message
     */
    @DeleteMapping("/{zoneId}")
    public ResponseEntity<ZoneDTO> deleteZone(@PathVariable String zoneId) {
        zoneService.deleteZone(zoneId);
        return ResponseEntity.ok(ZoneDTO.builder()
                .zoneId(zoneId)
                .message(STATUS_DELETED)
                .build());
    }

    /**
     * Force-stops and removes all zones (processes, filesystem, database).
     */
    @DeleteMapping
    public ResponseEntity<Void> deleteAllZones() {
        zoneService.deleteAllZones();
        return ResponseEntity.noContent().build();
    }
}
