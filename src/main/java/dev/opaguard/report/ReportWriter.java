package dev.opaguard.report;

import dev.opaguard.domain.GuardReport;

import java.nio.file.Path;

/**
 * Output port for persisting a complete guard report.
 *
 * @author Shelton Bumhe
 */
public interface ReportWriter {
    /**
     * Writes a report to the requested destination.
     *
     * @param report immutable report to serialize
     * @param output destination path
     */
    void write(GuardReport report, Path output);
}
