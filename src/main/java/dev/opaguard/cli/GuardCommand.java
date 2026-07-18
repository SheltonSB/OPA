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
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class GuardCommand implements ApplicationRunner {
    private final GuardProperties properties;
    private final DatasetLoader datasetLoader;
    private final BenchmarkRunner benchmarkRunner;
    private final RegressionAnalyzer analyzer;
    private final MarkdownReportWriter markdownWriter;
    private final JsonReportWriter jsonWriter;
    private volatile int exitCode = 2;

    public GuardCommand(
            GuardProperties properties,
            DatasetLoader datasetLoader,
            BenchmarkRunner benchmarkRunner,
            RegressionAnalyzer analyzer,
            MarkdownReportWriter markdownWriter,
            JsonReportWriter jsonWriter) {
        this.properties = properties;
        this.datasetLoader = datasetLoader;
        this.benchmarkRunner = benchmarkRunner;
        this.analyzer = analyzer;
        this.markdownWriter = markdownWriter;
        this.jsonWriter = jsonWriter;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var cases = datasetLoader.load(properties.benchmarkDataset());
            Duration timeout = Duration.ofSeconds(properties.processTimeoutSeconds());
            PolicyBenchmark baseline = benchmarkRunner.run(
                    "main", properties.baselinePolicy(), properties.query(), cases,
                    properties.warmupIterations(), properties.minimumIterations(), timeout);
            PolicyBenchmark candidate = benchmarkRunner.run(
                    "pr", properties.candidatePolicy(), properties.query(), cases,
                    properties.warmupIterations(), properties.minimumIterations(), timeout);
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

    public int exitCode() {
        return exitCode;
    }
}
