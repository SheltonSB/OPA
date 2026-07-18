package dev.opaguard;

import dev.opaguard.cli.GuardCommand;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
        String[] normalizedArgs = normalizeArguments(args);
        String mode = mode(normalizedArgs);
        SpringApplicationBuilder builder = new SpringApplicationBuilder(OpaGuardApplication.class)
                .web("cli".equals(mode) || "migration".equals(mode) ? WebApplicationType.NONE : WebApplicationType.SERVLET)
                .logStartupInfo(false);
        if ("cli".equals(mode)) {
            builder.properties("spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");
        }
        ConfigurableApplicationContext context = builder.run(normalizedArgs);
        if ("cli".equals(mode)) {
            int exitCode = context.getBean(GuardCommand.class).exitCode();
            context.close();
            System.exit(exitCode);
        } else if ("migration".equals(mode)) {
            context.close();
            System.exit(0);
        }
    }

    /**
     * Converts the user-friendly {@code --config path} option into the Spring Boot
     * configuration location understood during environment bootstrap. Spring must
     * receive this option before the application context is created; adding it as
     * a regular bean property would be too late for loading {@code opa-guard.yml}.
     *
     * <p>The long and equals forms are both accepted ({@code --config path} and
     * {@code --config=path}). Existing {@code --spring.config.additional-location}
     * values are preserved and combined with the explicit config path.</p>
     *
     * @param args raw launcher arguments (never {@code null})
     * @return arguments safe to pass to {@link SpringApplicationBuilder#run(String...)}
     * @throws IllegalArgumentException when {@code --config} has no path
     */
    static String[] normalizeArguments(String[] args) {
        Objects.requireNonNull(args, "args");
        if (!containsConfigArgument(args)) {
            return Arrays.copyOf(args, args.length);
        }

        List<String> normalized = new ArrayList<>();
        List<String> additionalLocations = new ArrayList<>();
        List<String> requestedLocations = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if ("--config".equals(argument)) {
                if (++index >= args.length || args[index].isBlank() || args[index].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for --config; expected a file path");
                }
                requestedLocations.add(toConfigLocation(args[index]));
            } else if (argument.startsWith("--config=")) {
                String value = argument.substring("--config=".length());
                if (value.isBlank()) {
                    throw new IllegalArgumentException("Missing value for --config; expected a file path");
                }
                requestedLocations.add(toConfigLocation(value));
            } else if (argument.startsWith("--spring.config.additional-location=")) {
                additionalLocations.add(argument.substring("--spring.config.additional-location=".length()));
            } else if ("--spring.config.additional-location".equals(argument)) {
                if (++index >= args.length || args[index].isBlank() || args[index].startsWith("--")) {
                    throw new IllegalArgumentException(
                            "Missing value for --spring.config.additional-location");
                }
                additionalLocations.add(args[index]);
            } else {
                normalized.add(argument);
            }
        }
        additionalLocations.addAll(requestedLocations);
        normalized.add("--spring.config.additional-location=" + String.join(",", additionalLocations));
        return normalized.toArray(String[]::new);
    }

    private static boolean containsConfigArgument(String[] args) {
        for (String argument : args) {
            if ("--config".equals(argument) || argument.startsWith("--config=")) {
                return true;
            }
        }
        return false;
    }

    private static String toConfigLocation(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("file:") || trimmed.startsWith("classpath:")) {
            return trimmed;
        }
        // Restrict the convenience option to local files. This avoids accidentally
        // enabling remote configuration retrieval (and the associated SSRF risk).
        Path path = Path.of(trimmed).toAbsolutePath().normalize();
        return "file:" + path;
    }

    private static String mode(String[] args) {
        for (String argument : args) {
            if (argument.startsWith("--opa-guard.mode=")) {
                return argument.substring("--opa-guard.mode=".length()).trim().toLowerCase(Locale.ROOT);
            }
        }
        return System.getenv().getOrDefault("OPA_GUARD_MODE", "cli").trim().toLowerCase(Locale.ROOT);
    }
}
