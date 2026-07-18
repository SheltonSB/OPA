package dev.opaguard.platform.api;

import dev.opaguard.platform.port.BenchmarkReportRepository;
import dev.opaguard.platform.security.TenantAuthorizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/**
 * Serves completed report projections in JSON, Markdown, or encoded HTML.
 *
 * @author Shelton Bumhe
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/benchmark-jobs/{jobId}/report")
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "coordinator")
public class BenchmarkReportController {
    private final BenchmarkReportRepository reports;
    private final TenantAuthorizer authorizer;

    public BenchmarkReportController(BenchmarkReportRepository reports, TenantAuthorizer authorizer) {
        this.reports = reports; this.authorizer = authorizer;
    }

    /**
     * Returns a completed report in the requested representation.
     *
     * @param organizationId tenant path identifier
     * @param jobId benchmark job identifier
     * @param format {@code json}, {@code markdown}, {@code md}, or {@code html}
     * @param authentication authenticated JWT principal
     * @return report body with matching media type
     */
    @GetMapping
    public ResponseEntity<String> get(@PathVariable UUID organizationId, @PathVariable UUID jobId,
                                      @RequestParam(defaultValue = "json") String format,
                                      Authentication authentication) {
        authorizer.requireAccess(organizationId, authentication);
        var report = reports.find(organizationId, jobId)
                .orElseThrow(() -> new BenchmarkJobController.JobNotFoundException(jobId));
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "json" -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(report.reportJson());
            case "markdown", "md" -> ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .body(report.markdown());
            case "html" -> ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(report.html());
            default -> throw new IllegalArgumentException("Unsupported report format");
        };
    }
}
