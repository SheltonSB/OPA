package dev.opaguard.platform.analysis;

import dev.opaguard.exception.GuardException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Computes explainable static complexity indicators from Rego source files.
 *
 * <p>The weighted score is a heuristic for change detection and advice. It is
 * not treated as a semantic parser or an authorization decision.</p>
 *
 * @author Shelton Bumhe
 */
@Component
public class RegoComplexityAnalyzer {
    private static final Pattern RULE = Pattern.compile("(?m)^\\s*[A-Za-z_][A-Za-z0-9_]*\\s*(?:if|:=|=)");
    private static final Pattern LOOP = Pattern.compile("\\bsome\\s+\\w+\\s+in\\s+|\\[_]|\\[[A-Za-z_][A-Za-z0-9_]*]");
    private static final Pattern COMPREHENSION = Pattern.compile("[\\[{][^\\n]*\\|[^\\n]*[\\]}]");

    /**
     * Analyzes all Rego files below a policy root.
     *
     * @param policyRoot verified local policy directory
     * @return source counts and weighted complexity score
     */
    public ComplexityMetrics analyze(Path policyRoot) {
        long files = 0, lines = 0, rules = 0, traversals = 0, comprehensions = 0;
        try (var paths = Files.walk(policyRoot)) {
            for (Path path : paths.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".rego")).toList()) {
                String source = Files.readString(path);
                files++;
                lines += source.lines().count();
                rules += RULE.matcher(source).results().count();
                traversals += LOOP.matcher(source).results().count();
                comprehensions += COMPREHENSION.matcher(source).results().count();
            }
        } catch (IOException exception) {
            throw new GuardException("Unable to analyze Rego complexity", exception);
        }
        long score = rules * 2 + traversals * 10 + comprehensions * 15 + lines / 20;
        return new ComplexityMetrics(files, lines, rules, traversals, comprehensions, score);
    }

    /**
     * Static Rego source measurements.
     *
     * @param files Rego file count
     * @param lines source line count
     * @param rules estimated rule count
     * @param traversals estimated collection traversal count
     * @param comprehensions estimated comprehension count
     * @param score weighted complexity indicator
     * @author Shelton Bumhe
     */
    public record ComplexityMetrics(long files, long lines, long rules, long traversals, long comprehensions, long score) {}
}
