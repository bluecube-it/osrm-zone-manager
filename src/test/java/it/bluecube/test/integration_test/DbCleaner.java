package it.bluecube.test.integration_test;

import it.bluecube.osrmzonemanager.zone.ZoneRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

@TestComponent
public class DbCleaner implements TestExecutionListener {

    @Autowired
    private ZoneRepository zoneRepository;

    public void run() {
        zoneRepository.deleteAllInBatch();
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        run();
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        testContext.getApplicationContext()
                .getAutowireCapableBeanFactory()
                .autowireBean(this);
    }
}
