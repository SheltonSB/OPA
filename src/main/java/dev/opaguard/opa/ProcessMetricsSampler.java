package dev.opaguard.opa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Samples child-process CPU time and resident memory with bounded lifecycle overhead.
 *
 * <p>Sampling is best effort because a short-lived OPA process may terminate
 * between process discovery and operating-system metric reads.</p>
 *
 * @author Shelton Bumhe
 */
final class ProcessMetricsSampler implements AutoCloseable {
    private final Process process;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;
    private volatile long peakRssBytes;
    private volatile long latestCpuNanos;

    ProcessMetricsSampler(Process process) {
        this.process = process;
        sample();
        this.thread = Thread.ofPlatform()
                .name("opa-metrics-" + process.pid())
                .daemon(true)
                .start(this::sampleUntilStopped);
    }

    private void sampleUntilStopped() {
        while (running.get() && process.isAlive()) {
            sample();
            try {
                Thread.sleep(Duration.ofMillis(25));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        sampleCpu();
    }

    private void sample() {
        sampleCpu();
        try {
            peakRssBytes = Math.max(peakRssBytes, currentRssBytes(process.pid()));
        } catch (IOException | NumberFormatException ignored) {
            // RSS is best-effort because the OPA process can end between discovery and sampling.
        }
    }

    private void sampleCpu() {
        try {
            process.info().totalCpuDuration()
                    .map(Duration::toNanos)
                    .ifPresent(value -> latestCpuNanos = Math.max(latestCpuNanos, value));
        } catch (RuntimeException ignored) {
            // Some hardened runtimes deny process accounting after the child has exited.
        }
    }

    static long currentRssBytes(long pid) throws IOException {
        Path procStatus = Path.of("/proc", Long.toString(pid), "status");
        if (Files.isReadable(procStatus)) {
            return Files.readAllLines(procStatus).stream()
                    .filter(line -> line.startsWith("VmRSS:"))
                    .findFirst()
                    .map(line -> line.replaceAll("[^0-9]", ""))
                    .filter(value -> !value.isBlank())
                    .map(Long::parseLong)
                    .map(kib -> kib * 1024L)
                    .orElse(0L);
        }

        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            Path psExecutable = Files.isExecutable(Path.of("/bin/ps")) ? Path.of("/bin/ps") : Path.of("/usr/bin/ps");
            if (!Files.isExecutable(psExecutable)) return 0L;
            Process ps = new ProcessBuilder(psExecutable.toString(), "-o", "rss=", "-p", Long.toString(pid)).start();
            try {
                if (!ps.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    ps.destroyForcibly();
                    return 0L;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0L;
            }
            String rss = new String(ps.getInputStream().readNBytes(1024)).trim();
            return rss.isBlank() ? 0L : Long.parseLong(rss) * 1024L;
        }
        return 0L;
    }

    long peakRssBytes() {
        return peakRssBytes;
    }

    long cpuNanos() {
        return latestCpuNanos;
    }

    @Override
    public void close() {
        running.set(false);
        thread.interrupt();
        try {
            thread.join(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        sampleCpu();
    }
}
