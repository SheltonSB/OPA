package dev.opaguard;

import dev.opaguard.cli.GuardCommand;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bootstraps the guard in CLI, migration, or distributed service mode.
 *
 * <p>The mode is selected with {@code --opa-guard.mode} or the
 * {@code OPA_GUARD_MODE} environment variable. CLI mode intentionally excludes
 * database auto-configuration so it can run as a lightweight CI executable.</p>
 *
 * @author Shelton Bumhe
 */
@SpringBootApplication
@EnableScheduling
public class OpaGuardApplication {

    /**
     * Starts the Spring application and translates terminal modes into process exit codes.
     *
     * @param args command-line arguments supplied by the launcher
     */
    public static void main(String[] args) {
        String mode = mode(args);
        SpringApplicationBuilder builder = new SpringApplicationBuilder(OpaGuardApplication.class)
                .web("cli".equals(mode) || "migration".equals(mode) ? WebApplicationType.NONE : WebApplicationType.SERVLET)
                .logStartupInfo(false);
        if ("cli".equals(mode)) {
            builder.properties("spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");
        }
        ConfigurableApplicationContext context = builder.run(args);
        if ("cli".equals(mode)) {
            int exitCode = context.getBean(GuardCommand.class).exitCode();
            context.close();
            System.exit(exitCode);
        } else if ("migration".equals(mode)) {
            context.close();
            System.exit(0);
        }
    }

    private static String mode(String[] args) {
        for (String argument : args) {
            if (argument.startsWith("--opa-guard.mode=")) {
                return argument.substring("--opa-guard.mode=".length()).trim().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return System.getenv().getOrDefault("OPA_GUARD_MODE", "cli").trim().toLowerCase(java.util.Locale.ROOT);
    }
}
