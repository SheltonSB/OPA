package dev.opaguard.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.analysis.RegressionAnalyzer;
import dev.opaguard.benchmark.BenchmarkRunner;
import dev.opaguard.benchmark.DatasetLoader;
import dev.opaguard.config.GuardProperties;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.domain.MetricComparison;
import dev.opaguard.domain.PolicyBenchmark;
import dev.opaguard.exception.GuardException;
import dev.opaguard.report.JsonReportWriter;
import dev.opaguard.report.MarkdownReportWriter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/**
 * Implements the repository-aware developer commands.
 *
 * <p>{@code compare} materializes detached Git worktrees under a secure
 * temporary directory and runs the existing benchmark engine against copied
 * policy trees. The active checkout is never checked out, reset, or deleted.</p>
 *
 * @author Shelton Bumhe
 */
@Service
public class DeveloperCommandService {
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.-]*)\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s*(?:default\\s+)?([A-Za-z_][A-Za-z0-9_-]*)\\s*(?::=|if|\\{)");
    private final GuardProperties properties;
    private final DatasetLoader datasetLoader;
    private final BenchmarkRunner benchmarkRunner;
    private final RegressionAnalyzer analyzer;
    private final MarkdownReportWriter markdownWriter;
    private final JsonReportWriter jsonWriter;
    private final ObjectMapper objectMapper;

    /**
     * Creates the developer command service.
     *
     * @param properties application defaults and configured thresholds
     * @param datasetLoader dataset validator
     * @param benchmarkRunner paired benchmark engine
     * @param analyzer regression analyzer
     * @param markdownWriter Markdown report adapter
     * @param jsonWriter JSON report adapter
     * @param objectMapper constrained JSON mapper
     */
    public DeveloperCommandService(GuardProperties properties, DatasetLoader datasetLoader,
                                   BenchmarkRunner benchmarkRunner, RegressionAnalyzer analyzer,
                                   MarkdownReportWriter markdownWriter, JsonReportWriter jsonWriter,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.datasetLoader = datasetLoader;
        this.benchmarkRunner = benchmarkRunner;
        this.analyzer = analyzer;
        this.markdownWriter = markdownWriter;
        this.jsonWriter = jsonWriter;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes a developer command.
     *
     * @param command command name
     * @param arguments parsed Spring command-line arguments
     * @return stable process exit code
     */
    public int execute(String command, ApplicationArguments arguments) {
        try {
            return switch (command) {
                case "init" -> init(arguments);
                case "validate" -> validate(arguments);
                case "compare" -> compare(arguments);
                case "doctor" -> doctor(arguments);
                default -> throw new CliFailure(CliExitCode.INVALID_CONFIGURATION,
                        "Unknown command '" + command + "'. Use one of: init, validate, compare, doctor.");
            };
        } catch (CliFailure failure) {
            System.err.println(failure.getMessage());
            return failure.exitCode().value();
        } catch (Exception exception) {
            System.err.println("OPA Policy Performance Guard internal error: " + exception.getMessage());
            return CliExitCode.INTERNAL_ERROR.value();
        }
    }

    private int compare(ApplicationArguments arguments) {
        CompareOptions options = CompareOptions.from(arguments, properties);
        GitRepository repository = GitRepository.discover(Path.of("."));
        GitRepository.ResolvedRef base = repository.resolveBase(options.base());
        GitRepository.ResolvedRef candidate = repository.resolveCandidate(options.candidate());
        boolean includeWorkingTree = !options.committedOnly() && "HEAD".equals(options.candidate());
        Path relativePolicy = relativeRepositoryPath(options.policyPath(), "--policy-path");
        Path relativeDataset = relativeRepositoryPath(options.datasetPath(), "--dataset");
        boolean uncommitted = includeWorkingTree && repository.hasWorkingTreeChanges(relativePolicy);

        try (MaterializedComparison materialized = MaterializedComparison.create(repository, base, candidate,
                relativePolicy, relativeDataset, includeWorkingTree, properties, options.query())) {
            String candidateLabel = "HEAD".equals(options.candidate())
                    ? repository.currentBranch() : candidate.ref();
            printPlan(repository, base, candidate, candidateLabel, relativePolicy, relativeDataset, options.query(), uncommitted);
            validateOpa(properties.opaExecutable());
            validatePolicy(materialized.baselinePolicy(), properties.opaExecutable());
            validatePolicy(materialized.candidatePolicy(), properties.opaExecutable());
            List<dev.opaguard.domain.BenchmarkCase> cases;
            try {
                cases = datasetLoader.load(materialized.dataset());
            } catch (GuardException exception) {
                throw new CliFailure(CliExitCode.MISSING_INPUT,
                        "Benchmark dataset is invalid: " + exception.getMessage(), exception);
            }
            if (cases.isEmpty()) {
                throw new CliFailure(CliExitCode.MISSING_INPUT, "Benchmark dataset must contain at least one case");
            }
            validateQuery(materialized.baselinePolicy(), options.query(), properties.opaExecutable());
            validateQuery(materialized.candidatePolicy(), options.query(), properties.opaExecutable());

            GuardProperties benchmarkProperties = new GuardProperties(
                    properties.opaExecutable(), options.query(), materialized.baselinePolicy(),
                    materialized.candidatePolicy(), materialized.dataset(),
                    properties.maximumLatencyRegressionPercent(), properties.maximumMemoryRegressionPercent(),
                    properties.minimumIterations(), properties.warmupIterations(),
                    reportPath(properties.markdownOutput()), reportPath(properties.jsonOutput()),
                    properties.processTimeoutSeconds(), properties.failOnDecisionChange(), properties.policyPath());
            BenchmarkRunner.BenchmarkPair pair;
            try {
                pair = benchmarkRunner.runPaired(materialized.baselinePolicy(), materialized.candidatePolicy(),
                        benchmarkProperties.query(), cases, benchmarkProperties.warmupIterations(),
                        benchmarkProperties.minimumIterations(), Duration.ofSeconds(benchmarkProperties.processTimeoutSeconds()));
            } catch (GuardException exception) {
                throw classifyOpaFailure(exception);
            }
            GuardReport report = analyzer.analyze(pair.baseline(), pair.candidate(),
                    benchmarkProperties.maximumLatencyRegressionPercent(),
                    benchmarkProperties.maximumMemoryRegressionPercent(),
                    benchmarkProperties.failOnDecisionChange());
            markdownWriter.write(report, benchmarkProperties.markdownOutput());
            jsonWriter.write(report, benchmarkProperties.jsonOutput());
            printResult(report, benchmarkProperties.markdownOutput(), benchmarkProperties.jsonOutput(),
                    benchmarkProperties.failOnDecisionChange());
            return guardExitCode(report, benchmarkProperties.failOnDecisionChange()).value();
        } catch (CliFailure failure) {
            throw failure;
        } catch (GuardException exception) {
            throw classifyOpaFailure(exception);
        }
    }

    private int validate(ApplicationArguments arguments) {
        GitRepository repository = GitRepository.discover(Path.of("."));
        CompareOptions options = CompareOptions.from(arguments, properties);
        Path policy = resolveExistingPath(repository.root(), options.policyPath(), "policy path");
        Path dataset = resolveExistingPath(repository.root(), options.datasetPath(), "dataset");
        validateOpa(properties.opaExecutable());
        validatePolicy(policy, properties.opaExecutable());
        try {
            datasetLoader.load(dataset);
        } catch (GuardException exception) {
            throw new CliFailure(CliExitCode.MISSING_INPUT,
                    "Benchmark dataset is invalid: " + exception.getMessage(), exception);
        }
        validateQuery(policy, options.query(), properties.opaExecutable());
        System.out.println("OPA Policy Performance Guard validation: PASS");
        System.out.println("Repository: " + repository.repositoryName());
        System.out.println("Policy path: " + options.policyPath());
        System.out.println("Dataset: " + options.datasetPath());
        System.out.println("Query: " + options.query());
        return CliExitCode.PASS.value();
    }

    private int doctor(ApplicationArguments arguments) {
        boolean passed = true;
        passed &= check("Git", () -> GitRepository.discover(Path.of(".")));
        GitRepository repository = null;
        try {
            repository = GitRepository.discover(Path.of("."));
            System.out.println("Repository: PASS");
            System.out.println("Current branch: " + repository.currentBranch());
            System.out.println("Default base: " + repository.resolveBase(arguments.getOptionValues("base") == null
                    ? null : arguments.getOptionValues("base").get(0)).ref());
        } catch (CliFailure failure) {
            System.out.println("Repository: FAIL — " + failure.getMessage());
            passed = false;
        }
        passed &= check("Java 21", () -> {
            if (Runtime.version().feature() < 21) {
                throw new CliFailure(CliExitCode.INVALID_CONFIGURATION, "Java 21 or newer is required");
            }
        });
        passed &= check("OPA " + properties.opaExecutable(), () -> validateOpa(properties.opaExecutable()));
        if (repository != null) {
            GitRepository discovered = repository;
            CompareOptions options = CompareOptions.from(arguments, properties);
            passed &= check("Policy path", () -> resolveExistingPath(discovered.root(), options.policyPath(), "policy path"));
            passed &= check("Dataset", () -> {
                Path dataset = resolveExistingPath(discovered.root(), options.datasetPath(), "dataset");
                try {
                    datasetLoader.load(dataset);
                } catch (GuardException exception) {
                    throw new CliFailure(CliExitCode.MISSING_INPUT,
                            "Benchmark dataset is invalid: " + exception.getMessage(), exception);
                }
            });
            passed &= check("Query " + options.query(), () -> {
                Path policy = resolveExistingPath(discovered.root(), options.policyPath(), "policy path");
                validatePolicy(policy, properties.opaExecutable());
                validateQuery(policy, options.query(), properties.opaExecutable());
            });
        }
        return passed ? CliExitCode.PASS.value() : CliExitCode.INVALID_CONFIGURATION.value();
    }

    private int init(ApplicationArguments arguments) {
        GitRepository repository = GitRepository.discover(Path.of("."));
        boolean force = arguments.containsOption("force");
        Path root = repository.root();
        String discoveredQuery = discoverQuery(root.resolve("policy")).orElse(properties.query());
        writeIfAllowed(root.resolve("opa-guard.yml"), "opa-guard:\n"
                + "  opa-executable: opa\n"
                + "  query: " + discoveredQuery + "\n"
                + "  policy-path: policy\n"
                + "  benchmark-dataset: benchmark/dataset.json\n"
                + "  maximum-latency-regression-percent: 10\n"
                + "  maximum-memory-regression-percent: 15\n"
                + "  minimum-iterations: 500\n"
                + "  warmup-iterations: 25\n"
                + "  process-timeout-seconds: 30\n"
                + "  fail-on-decision-change: true\n"
                + "  markdown-output: build/reports/opa-guard.md\n"
                + "  json-output: build/reports/opa-guard.json\n", force);
        Path dataset = root.resolve("benchmark/dataset.json");
        writeIfAllowed(dataset, "[{\"id\":\"example\",\"input\":{\"user\":{\"role\":\"member\"},\"action\":\"read\"}}]\n", force);
        Path workflow = root.resolve(".github/workflows/opa-guard.yml");
        writeIfAllowed(workflow, "name: OPA Guard\n"
                + "on:\n  pull_request:\n    branches: [main]\n"
                + "jobs:\n  opa-guard:\n"
                + "    uses: SheltonSB/opa-policy-performance-guard/.github/workflows/opa-guard-reusable.yml@v1\n"
                + "    with:\n      policy-path: policy\n"
                + "      dataset-path: benchmark/dataset.json\n"
                + "      query: " + discoveredQuery + "\n", force);
        System.out.println("Initialized OPA Policy Performance Guard files (" + (force ? "forced" : "existing files preserved") + ").");
        return CliExitCode.PASS.value();
    }

    private void printPlan(GitRepository repository, GitRepository.ResolvedRef base,
                           GitRepository.ResolvedRef candidate, String candidateLabel, Path policy, Path dataset,
                           String query, boolean uncommitted) {
        System.out.println("OPA Policy Performance Guard\n");
        System.out.println("Repository: " + repository.repositoryName());
        System.out.println("Baseline: " + base.ref() + " @ " + base.shortCommit());
        System.out.println("Candidate: " + candidateLabel + " @ " + candidate.shortCommit());
        System.out.println("Policy path: " + policy);
        System.out.println("Dataset: " + dataset);
        System.out.println("Query: " + query);
        System.out.println("Uncommitted changes: " + (uncommitted ? "yes" : "no"));
        if (uncommitted) {
            System.out.println("Candidate includes uncommitted working-tree changes.");
        }
        System.out.println();
    }

    private void printResult(GuardReport report, Path markdown, Path json, boolean failOnDecisionChange) {
        System.out.println(markdownWriter.render(report));
        System.out.println("Decision mismatches: " + report.decisionMismatches().size());
        System.out.println("Average latency regression: " + metricRegression(report, "Average latency"));
        System.out.println("p95 regression: " + metricRegression(report, "p95 latency"));
        System.out.println("p99 regression: " + metricRegression(report, "p99 latency"));
        System.out.println("Memory regression: " + metricRegression(report, "Peak memory"));
        List<String> reasons = new ArrayList<>();
        if (failOnDecisionChange && !report.decisionMismatches().isEmpty()) {
            reasons.add("authorization decision changes detected");
        }
        report.comparisons().stream().filter(MetricComparison::thresholdExceeded)
                .map(MetricComparison::metric).forEach(metric -> reasons.add(metric + " exceeded its threshold"));
        System.out.println("Blocking reasons: " + (reasons.isEmpty() ? "none" : String.join("; ", reasons)));
        System.out.println("Reports: " + markdown + ", " + json);
    }

    private static String metricRegression(GuardReport report, String metric) {
        return report.comparisons().stream()
                .filter(comparison -> comparison.metric().startsWith(metric))
                .map(comparison -> String.format(Locale.ROOT, "%+.2f%%", comparison.regressionPercent()))
                .findFirst().orElse("n/a");
    }

    private static Optional<String> discoverQuery(Path policyRoot) {
        if (!Files.isDirectory(policyRoot)) {
            return Optional.empty();
        }
        try (var files = Files.walk(policyRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".rego")).toList()) {
                String packageName = null;
                for (String line : Files.readAllLines(file)) {
                    Matcher packageMatcher = PACKAGE.matcher(line);
                    if (packageMatcher.matches()) {
                        packageName = packageMatcher.group(1);
                        continue;
                    }
                    Matcher ruleMatcher = RULE.matcher(line);
                    if (packageName != null && ruleMatcher.find()) {
                        return Optional.of("data." + packageName + "." + ruleMatcher.group(1));
                    }
                }
            }
        } catch (IOException ignored) {
            // Initialization remains useful with the documented default query.
        }
        return Optional.empty();
    }

    static CliExitCode guardExitCode(GuardReport report, boolean failOnDecisionChange) {
        boolean decisions = failOnDecisionChange && !report.decisionMismatches().isEmpty();
        boolean latency = report.comparisons().stream()
                .anyMatch(comparison -> comparison.thresholdExceeded() && comparison.metric().toLowerCase(Locale.ROOT).contains("latency"));
        boolean memory = report.comparisons().stream()
                .anyMatch(comparison -> comparison.thresholdExceeded() && comparison.metric().toLowerCase(Locale.ROOT).contains("memory"));
        int failures = (decisions ? 1 : 0) + (latency ? 1 : 0) + (memory ? 1 : 0);
        if (failures > 1) {
            return CliExitCode.MULTIPLE_GUARD_FAILURES;
        }
        if (decisions) {
            return CliExitCode.DECISION_MISMATCH;
        }
        if (latency) {
            return CliExitCode.LATENCY_REGRESSION;
        }
        if (memory) {
            return CliExitCode.MEMORY_REGRESSION;
        }
        return CliExitCode.PASS;
    }

    private void validateOpa(String executable) {
        CommandResult result = runCommand(List.of(executable, "version"), Path.of("."));
        if (result.exitCode() != 0) {
            throw new CliFailure(CliExitCode.OPA_EXECUTION_FAILURE,
                    "OPA is unavailable or failed to start: " + message(result)
                            + ". Install OPA and ensure '" + executable + "' is on PATH.");
        }
    }

    private void validatePolicy(Path policy, String executable) {
        if (!Files.exists(policy)) {
            throw new CliFailure(CliExitCode.MISSING_INPUT, "Policy path does not exist: " + policy);
        }
        CommandResult result = runCommand(List.of(executable, "check", "--bundle", policy.toString()), policy.getParent());
        if (result.exitCode() != 0) {
            throw new CliFailure(CliExitCode.INVALID_POLICY,
                    "Rego compilation failed for " + policy + ": " + message(result));
        }
    }

    private void validateQuery(Path policy, String query, String executable) {
        CommandResult result = runCommand(List.of(executable, "eval", "--format=json", "--data", policy.toString(), query),
                policy.getParent());
        if (result.exitCode() != 0) {
            throw new CliFailure(CliExitCode.INVALID_POLICY,
                    "Query '" + query + "' could not be resolved for " + policy + ": " + message(result));
        }
        try {
            JsonNode document = objectMapper.readTree(result.stdout());
            if (!document.path("result").isArray() || document.path("result").isEmpty()) {
                throw new CliFailure(CliExitCode.INVALID_POLICY,
                        "Query '" + query + "' returned no decision in " + policy);
            }
        } catch (IOException exception) {
            throw new CliFailure(CliExitCode.INVALID_POLICY,
                    "OPA returned an invalid query result for '" + query + "'", exception);
        }
    }

    private static Path resolveExistingPath(Path root, String value, String label) {
        Path path = Path.of(value);
        Path resolved = path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
        if (!Files.exists(resolved)) {
            throw new CliFailure(CliExitCode.MISSING_INPUT,
                    "Could not find " + label + " \"" + value + "\" in " + root + ".");
        }
        return resolved;
    }

    private static Path relativeRepositoryPath(String value, String option) {
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new CliFailure(CliExitCode.INVALID_CONFIGURATION,
                    option + " must be a repository-relative path: " + value);
        }
        return path;
    }

    private Path reportPath(Path configured) {
        Path path = configured == null ? Path.of("build/reports/opa-guard.md") : configured;
        return path.isAbsolute() ? path : GitRepository.discover(Path.of(".")).root().resolve(path).normalize();
    }

    private static void writeIfAllowed(Path file, String content, boolean force) {
        try {
            if (Files.exists(file) && !force) {
                System.out.println("Preserved existing " + file);
                return;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content);
            System.out.println("Wrote " + file);
        } catch (IOException exception) {
            throw new CliFailure(CliExitCode.INVALID_CONFIGURATION, "Unable to write " + file + ": " + exception.getMessage(), exception);
        }
    }

    private boolean check(String label, Runnable action) {
        try {
            action.run();
            System.out.println(label + ": PASS");
            return true;
        } catch (RuntimeException exception) {
            System.out.println(label + ": FAIL — " + exception.getMessage());
            return false;
        }
    }

    private static CliFailure classifyOpaFailure(GuardException exception) {
        String message = exception.getMessage() == null ? "OPA execution failed" : exception.getMessage();
        return new CliFailure(message.toLowerCase(Locale.ROOT).contains("timed out")
                ? CliExitCode.BENCHMARK_TIMEOUT : CliExitCode.OPA_EXECUTION_FAILURE, message, exception);
    }

    private static CommandResult runCommand(List<String> command, Path directory) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).start();
            byte[] stdout = process.getInputStream().readNBytes(2 * 1024 * 1024);
            byte[] stderr = process.getErrorStream().readNBytes(2 * 1024 * 1024);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CliFailure(CliExitCode.OPA_EXECUTION_FAILURE, "Command timed out: " + String.join(" ", command));
            }
            return new CommandResult(process.exitValue(), new String(stdout), new String(stderr));
        } catch (CliFailure failure) {
            throw failure;
        } catch (IOException exception) {
            throw new CliFailure(CliExitCode.OPA_EXECUTION_FAILURE,
                    "Could not execute '" + command.get(0) + "'. Install it and ensure it is on PATH.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CliFailure(CliExitCode.OPA_EXECUTION_FAILURE, "Command was interrupted", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String message(CommandResult result) {
        String stderr = result.stderr().trim();
        return stderr.isBlank() ? result.stdout().trim() : stderr;
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {}

    private record CompareOptions(String base, String candidate, String policyPath, String datasetPath,
                                  String query, boolean committedOnly) {
        static CompareOptions from(ApplicationArguments arguments, GuardProperties properties) {
            return new CompareOptions(first(arguments, "base", null), first(arguments, "candidate", "HEAD"),
                    first(arguments, "policy-path", properties.policyPath() == null ? "policy" : properties.policyPath().toString()),
                    first(arguments, "dataset", properties.benchmarkDataset() == null
                            ? "benchmark/dataset.json" : properties.benchmarkDataset().toString()),
                    first(arguments, "query", properties.query()), arguments.containsOption("committed-only"));
        }

        private static String first(ApplicationArguments arguments, String key, String fallback) {
            List<String> values = arguments.getOptionValues(key);
            return values == null || values.isEmpty() || values.get(0).isBlank() ? fallback : values.get(0);
        }
    }

    private static final class MaterializedComparison implements AutoCloseable {
        private final GitRepository repository;
        private final Path temporaryRoot;
        private final Path baselineWorktree;
        private final Path candidateWorktree;
        private final Path baselinePolicy;
        private final Path candidatePolicy;
        private final Path dataset;
        private final Thread cleanupHook;

        private MaterializedComparison(GitRepository repository, Path temporaryRoot, Path baselineWorktree,
                                       Path candidateWorktree, Path baselinePolicy, Path candidatePolicy, Path dataset,
                                       Thread cleanupHook) {
            this.repository = repository;
            this.temporaryRoot = temporaryRoot;
            this.baselineWorktree = baselineWorktree;
            this.candidateWorktree = candidateWorktree;
            this.baselinePolicy = baselinePolicy;
            this.candidatePolicy = candidatePolicy;
            this.dataset = dataset;
            this.cleanupHook = cleanupHook;
        }

        static MaterializedComparison create(GitRepository repository, GitRepository.ResolvedRef base,
                                              GitRepository.ResolvedRef candidate, Path policy, Path dataset,
                                              boolean includeWorkingTree, GuardProperties properties, String query) {
            Path temporaryRoot = null;
            Path baselineWorktree = null;
            Path candidateWorktree = null;
            try {
                temporaryRoot = Files.createTempDirectory("opa-guard-");
                baselineWorktree = temporaryRoot.resolve("baseline-revision");
                repository.addWorktree(baselineWorktree, base.commit());
                Path candidateSource = repository.root();
                if (!includeWorkingTree) {
                    candidateWorktree = temporaryRoot.resolve("candidate-revision");
                    repository.addWorktree(candidateWorktree, candidate.commit());
                    candidateSource = candidateWorktree;
                }
                Path baselinePolicy = temporaryRoot.resolve("baseline/policy");
                Path candidatePolicy = temporaryRoot.resolve("candidate/policy");
                Path baselineSource = baselineWorktree.resolve(policy).normalize();
                if (!baselineSource.startsWith(baselineWorktree) || !Files.exists(baselineSource)) {
                    throw missingPolicy("baseline", policy, base);
                }
                Path candidatePolicySource = candidateSource.resolve(policy).normalize();
                if (!candidatePolicySource.startsWith(candidateSource) || !Files.exists(candidatePolicySource)) {
                    throw missingPolicy("candidate", policy, candidate);
                }
                copyTree(baselineSource, baselinePolicy);
                copyTree(candidatePolicySource, candidatePolicy);
                Path datasetOutput = temporaryRoot.resolve("benchmark/dataset.json");
                Path datasetSource = candidateSource.resolve(dataset).normalize();
                if (!datasetSource.startsWith(candidateSource) || !Files.exists(datasetSource)) {
                    throw new CliFailure(CliExitCode.MISSING_INPUT,
                            "Could not find dataset \"" + dataset + "\" in candidate revision " + candidate.ref());
                }
                copyFile(datasetSource, datasetOutput);
                Files.createDirectories(temporaryRoot.resolve("build/reports"));
                Path generatedConfig = temporaryRoot.resolve("opa-guard.generated.yml");
                Files.writeString(generatedConfig, generatedConfig(properties, query));
                MaterializedComparison[] holder = new MaterializedComparison[1];
                Thread cleanupHook = new Thread(() -> {
                    if (holder[0] != null) {
                        holder[0].close();
                    }
                }, "opa-guard-worktree-cleanup");
                MaterializedComparison materialized = new MaterializedComparison(repository, temporaryRoot,
                        baselineWorktree, candidateWorktree, baselinePolicy, candidatePolicy, datasetOutput, cleanupHook);
                holder[0] = materialized;
                Runtime.getRuntime().addShutdownHook(cleanupHook);
                return materialized;
            } catch (CliFailure failure) {
                if (baselineWorktree != null) {
                    repository.removeWorktree(baselineWorktree);
                }
                if (candidateWorktree != null) {
                    repository.removeWorktree(candidateWorktree);
                }
                deleteRecursively(temporaryRoot);
                throw failure;
            } catch (IOException exception) {
                if (baselineWorktree != null) {
                    repository.removeWorktree(baselineWorktree);
                }
                if (candidateWorktree != null) {
                    repository.removeWorktree(candidateWorktree);
                }
                deleteRecursively(temporaryRoot);
                throw new CliFailure(CliExitCode.INTERNAL_ERROR, "Unable to create temporary comparison workspace: " + exception.getMessage(), exception);
            }
        }

        private static CliFailure missingPolicy(String side, Path policy, GitRepository.ResolvedRef revision) {
            return new CliFailure(CliExitCode.MISSING_INPUT,
                    "Could not find policy path \"" + policy + "\" in " + side + " revision " + revision.ref()
                            + ".\n\n" + ("baseline".equals(side) ? "Baseline commit:\n" : "Candidate commit:\n")
                            + revision.shortCommit()
                            + "\n\nFix:\n- pass --policy-path <path>\n- or add policy files to the " + side + " branch");
        }

        private static String generatedConfig(GuardProperties properties, String query) {
            return "opa-guard:\n"
                    + "  opa-executable: '" + scalar(properties.opaExecutable()) + "'\n"
                    + "  query: '" + scalar(query) + "'\n"
                    + "  baseline-policy: baseline/policy\n"
                    + "  candidate-policy: candidate/policy\n"
                    + "  benchmark-dataset: benchmark/dataset.json\n"
                    + "  maximum-latency-regression-percent: " + properties.maximumLatencyRegressionPercent() + "\n"
                    + "  maximum-memory-regression-percent: " + properties.maximumMemoryRegressionPercent() + "\n"
                    + "  minimum-iterations: " + properties.minimumIterations() + "\n"
                    + "  warmup-iterations: " + properties.warmupIterations() + "\n";
        }

        private static String scalar(String value) {
            return value == null ? "" : value.replace("'", "''");
        }

        Path baselinePolicy() { return baselinePolicy; }
        Path candidatePolicy() { return candidatePolicy; }
        Path dataset() { return dataset; }

        @Override
        public void close() {
            if (cleanupHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(cleanupHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already in progress.
                }
            }
            repository.removeWorktree(baselineWorktree);
            repository.removeWorktree(candidateWorktree);
            deleteRecursively(temporaryRoot);
        }

        private static void copyFile(Path source, Path target) throws IOException {
            if (Files.isSymbolicLink(source) || !Files.isRegularFile(source)) {
                throw new CliFailure(CliExitCode.MISSING_INPUT, "Dataset must be a regular file: " + source);
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }

        private static void copyTree(Path source, Path target) throws IOException {
            if (Files.isSymbolicLink(source) || !Files.exists(source)) {
                throw new CliFailure(CliExitCode.MISSING_INPUT, "Policy path does not exist: " + source);
            }
            Files.createDirectories(target);
            if (Files.isRegularFile(source)) {
                Files.copy(source, target.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(directory)) {
                        throw new CliFailure(CliExitCode.INVALID_POLICY, "Policy trees must not contain symbolic links: " + directory);
                    }
                    Files.createDirectories(target.resolve(source.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        throw new CliFailure(CliExitCode.INVALID_POLICY, "Policy trees must not contain symbolic links: " + file);
                    }
                    Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        private static void deleteRecursively(Path root) {
            if (root == null || !Files.exists(root)) {
                return;
            }
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best-effort cleanup; worktree removal has already run.
                    }
                });
            } catch (IOException ignored) {
                // Best-effort cleanup on interruption or process shutdown.
            }
        }
    }
}
