package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.test.BaseUnitTest;
import org.junit.jupiter.api.Test;

class PortAllocatorServiceReleasePortTest extends BaseUnitTest {

    @Test
    void shouldNotThrowOnReleasePort() {
        PortAllocatorService target = new PortAllocatorService(null, null);

        target.releasePort("osrm", 5001);
        target.releasePort("vroom", 3001);
    }
}
