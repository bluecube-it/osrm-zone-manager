package it.bluecube.osrmzonemanager.zone;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.Objects;

/**
 * Persistent zone record stored in H2. Tracks zone lifecycle status, ports, PIDs,
 * content hashes, and source geojson.
 */
@Entity
@Table(name = "zones")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ZoneEntity {

    @Id
    @EqualsAndHashCode.Include
    private String zoneId;

    @Version
    @Builder.Default
    private Long version = 0L;

    private String polygonHash;

    @Builder.Default
    private String lineStringsHash = "";

    private String basePbfMtime;

    private String status;

    private int osrmPort;

    private int vroomPort;

    @Builder.Default
    private long osrmPid = 0;

    @Builder.Default
    private long vroomPid = 0;

    private Instant createdAt;

    private Instant lastAccess;

    private Instant lastBuildAt;

    @Builder.Default
    private String error = "";

    @Lob
    @ToString.Exclude
    private String polygonGeojson;

    @Lob
    @ToString.Exclude
    private String lineStringsGeojson;

    /**
     * Checks whether this zone's content (polygon, lineStrings, and base PBF version)
     * matches the given hashes — i.e. whether a request with this content can reuse
     * this zone instead of triggering a rebuild.
     */
    public boolean matchesContent(String polygonHash, String lineStringsHash, String baseMtime) {
        return Objects.equals(this.polygonHash, polygonHash)
                && Objects.equals(this.lineStringsHash, lineStringsHash)
                && Objects.equals(this.basePbfMtime, baseMtime);
    }
}
