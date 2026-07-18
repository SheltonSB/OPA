package dev.opaguard.report;

import dev.opaguard.domain.GuardReport;

import java.nio.file.Path;

public interface ReportWriter {
    void write(GuardReport report, Path output);
}
