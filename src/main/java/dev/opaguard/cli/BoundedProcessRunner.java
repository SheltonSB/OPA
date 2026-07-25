package dev.opaguard.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes an argument-list command while draining both child-process streams
 * concurrently and enforcing bounded output and runtime limits.
 *
 * <p>Reading stdout and stderr serially can deadlock a child that fills the
 * unread pipe. Keeping this small adapter package-private lets the Git and
 * developer-command adapters share the safe process lifecycle without
 * widening the CLI API.</p>
 *
 * @author Shelton Bumhe
 */
final class BoundedProcessRunner {
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private BoundedProcessRunner() {
    }

    static Result run(List<String> command, Path directory, Duration timeout, int maxOutputBytes)
            throws IOException, InterruptedException, TimeoutException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        try (ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(
                    () -> readBounded(process.getInputStream(), maxOutputBytes), ioExecutor);
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(
                    () -> readBounded(process.getErrorStream(), maxOutputBytes), ioExecutor);
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    terminate(process);
                    throw new TimeoutException("Process timed out after " + timeout);
                }
                return new Result(process.exitValue(), await(stdout), await(stderr));
            } finally {
                if (process.isAlive()) {
                    terminate(process);
                }
            }
        }
    }

    private static byte[] await(CompletableFuture<byte[]> output)
            throws IOException, InterruptedException, TimeoutException {
        try {
            return output.get(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof OutputLimitException limit) {
                throw new IOException(limit.getMessage(), limit);
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Unable to capture process output", cause);
        }
    }

    private static byte[] readBounded(InputStream stream, int maxOutputBytes) {
        try (stream) {
            byte[] output = stream.readNBytes(maxOutputBytes + 1);
            if (output.length > maxOutputBytes) {
                throw new OutputLimitException("Process output exceeded " + maxOutputBytes + " bytes");
            }
            return output;
        } catch (IOException exception) {
            throw new OutputLimitException("Unable to capture process output", exception);
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(child -> {
            child.destroy();
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        });
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    static String text(byte[] output) {
        return new String(output, StandardCharsets.UTF_8).trim();
    }

    record Result(int exitCode, byte[] stdout, byte[] stderr) {
        String stdoutText() {
            return text(stdout);
        }

        String stderrText() {
            return text(stderr);
        }
    }

    private static final class OutputLimitException extends RuntimeException {
        private OutputLimitException(String message) {
            super(message);
        }

        private OutputLimitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
