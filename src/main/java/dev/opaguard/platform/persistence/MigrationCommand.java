package dev.opaguard.platform.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Terminates migration mode after Spring Boot's Flyway lifecycle completes.
 *
 * @author Shelton Bumhe
 */
@Component
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "migration")
public class MigrationCommand implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        // Flyway auto-configuration completes before runners are invoked.
        System.out.println("OPA Guard database migration completed");
    }
}
