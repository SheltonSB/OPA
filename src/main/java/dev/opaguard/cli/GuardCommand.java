package dev.opaguard.cli;

import dev.opaguard.analysis.RegressionAnalyzer;
import dev.opaguard.benchmark.BenchmarkRunner;
import dev.opaguard.benchmark.DatasetLoader;
import dev.opaguard.config.GuardProperties;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.domain.PolicyBenchmark;
import dev.opaguard.report.JsonReportWriter;
import dev.opaguard.report.MarkdownReportWriter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Runs the single-process CI workflow and exposes a stable process exit code.
 *
 * <p>The legacy no-command invocation retains exit codes {@code 0}, {@code 1},
 * and {@code 2}. The developer commands ({@code init}, {@code validate},
 * {@code compare}, and {@code doctor}) delegate to {@link DeveloperCommandService}
 * and expose the documented stable exit-code table.</p>
 *
 * @author Shelton Bumhe
 */
@Component
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "cli", matchIfMissing = true)
public class GuardCommand implements ApplicationRunner {
    private final GuardProperties properties;
    private final DatasetLoader datasetLoader;
    private final BenchmarkRunner benchmarkRunner;
    private final RegressionAnalyzer analyzer;
    private final MarkdownReportWriter markdownWriter;
    private final JsonReportWriter jsonWriter;
    private final DeveloperCommandService developerCommands;
    private volatile int exitCode = 2;

    public GuardCommand(
            GuardProperties properties,
            DatasetLoader datasetLoader,
            BenchmarkRunner benchmarkRunner,
            RegressionAnalyzer analyzer,
            MarkdownReportWriter markdownWriter,
            JsonReportWriter jsonWriter,
            DeveloperCommandService developerCommands) {
        this.properties = properties;
        this.datasetLoader = datasetLoader;
        this.benchmarkRunner = benchmarkRunner;
        this.analyzer = analyzer;
        this.markdownWriter = markdownWriter;
        this.jsonWriter = jsonWriter;
        this.developerCommands = developerCommands;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = args.getNonOptionArgs().stream().findFirst().orElse(null);
        if (command != null) {
            exitCode = developerCommands.execute(command, args);
            return;
        }
        try {
            var cases = datasetLoader.load(properties.benchmarkDataset());
            Duration timeout = Duration.ofSeconds(properties.processTimeoutSeconds());
            BenchmarkRunner.BenchmarkPair pair = benchmarkRunner.runPaired(
                    properties.baselinePolicy(), properties.candidatePolicy(), properties.query(), cases,
                    properties.warmupIterations(), properties.minimumIterations(), timeout);
            PolicyBenchmark baseline = pair.baseline();
            PolicyBenchmark candidate = pair.candidate();
            GuardReport report = analyzer.analyze(
                    baseline, candidate,
                    properties.maximumLatencyRegressionPercent(),
                    properties.maximumMemoryRegressionPercent(),
                    properties.failOnDecisionChange());
            markdownWriter.write(report, properties.markdownOutput());
            jsonWriter.write(report, properties.jsonOutput());
            System.out.println(markdownWriter.render(report));
            System.out.printf("Reports: %s, %s%n", properties.markdownOutput(), properties.jsonOutput());
            exitCode = report.passed() ? 0 : 1;
        } catch (Exception exception) {
            System.err.println("OPA Policy Performance Guard failed: " + exception.getMessage());
            exitCode = 2;
        }
    }

    /**
     * Returns the result to be used by the application launcher.
     *
     * @return {@code 0} for pass, {@code 1} for regression, or {@code 2} for an execution error
     */
    public int exitCode() {
        return exitCode;
    }
}
