package dev.opaguard.analysis;

import dev.opaguard.domain.DecisionMismatch;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Produces actionable, deterministic advice for a failed policy comparison.
 *
 * <p>The advisor uses correctness results and a deliberately conservative Rego
 * source heuristic. Its output is guidance, not a substitute for OPA profiling.</p>
 *
 * @author Shelton Bumhe
 */
@Component
public class PolicyChangeAdvisor {
    private static final Pattern ARRAY_TRAVERSAL = Pattern.compile("\\bsome\\s+\\w+\\s+in\\s+|\\[[_a-zA-Z][^]]*]", Pattern.MULTILINE);

    /**
     * Explains the most likely cause of a failed comparison.
     *
     * @param baseline baseline policy file or directory
     * @param candidate candidate policy file or directory
     * @param mismatches decision differences found by the analyzer
     * @param performanceRegression whether a performance threshold was exceeded
     * @return advice suitable for human-readable reports
     */
    public Advice advise(Path baseline, Path candidate, List<DecisionMismatch> mismatches, boolean performanceRegression) {
        if (!mismatches.isEmpty() && !performanceRegression) {
            return new Advice(
                    "Policy decisions changed for " + mismatches.size() + " benchmark case(s)",
                    "Review the changed Rego logic or explicitly update the expected benchmark decisions.");
        }
        if (performanceRegression && traversalCount(candidate) > traversalCount(baseline)) {
            return new Advice(
                    "Additional array traversal detected in the candidate policy",
                    "Prefer keyed objects or indexed lookups over repeated array iteration where the policy semantics allow it.");
        }
        if (performanceRegression) {
            return new Advice(
                    "Candidate policy exceeded a configured performance threshold",
                    "Profile the changed rules with `opa eval --profile` and remove repeated scans or recomputation.");
        }
        return Advice.none();
    }

    private long traversalCount(Path path) {
        try (Stream<Path> files = Files.isDirectory(path) ? Files.walk(path) : Stream.of(path)) {
            return files.filter(file -> file.toString().toLowerCase(Locale.ROOT).endsWith(".rego"))
                    .mapToLong(this::countInFile)
                    .sum();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private long countInFile(Path file) {
        try {
            Matcher matcher = ARRAY_TRAVERSAL.matcher(Files.readString(file));
            long count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        } catch (IOException ignored) {
            return 0;
        }
    }

    /**
     * Human-readable diagnosis and recommended remediation.
     *
     * @param cause concise description of the detected condition
     * @param recommendation suggested next action
     * @author Shelton Bumhe
     */
    public record Advice(String cause, String recommendation) {
        /**
         * Returns neutral advice for a successful comparison.
         *
         * @return a no-action-required result
         */
        public static Advice none() {
            return new Advice("None", "No action required.");
        }
    }
}
