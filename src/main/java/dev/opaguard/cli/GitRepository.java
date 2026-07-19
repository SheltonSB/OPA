package dev.opaguard.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Small, argument-list-only adapter around Git for developer commands.
 *
 * <p>The adapter never invokes a shell and never changes the caller's checked
 * out branch. Worktrees created by this class are detached and are removed by
 * {@link #removeWorktree(Path)} when a command completes.</p>
 *
 * @author Shelton Bumhe
 */
public final class GitRepository {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;

    private final Path root;

    private GitRepository(Path root) {
        this.root = root;
    }

    /**
     * Locates the repository containing a path.
     *
     * @param start starting directory
     * @return repository adapter
     * @throws CliFailure when Git is unavailable or the path is not in a repository
     */
    public static GitRepository discover(Path start) {
        Path directory = start.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new CliFailure(CliExitCode.GIT_RESOLUTION_FAILURE,
                    "Current directory does not exist: " + directory);
        }
        CommandResult result = run(directory, List.of("git", "rev-parse", "--show-toplevel"));
        if (result.exitCode() != 0) {
            throw new CliFailure(CliExitCode.GIT_RESOLUTION_FAILURE,
                    "Could not find a Git repository from " + directory
                            + ". Run this command inside a Git working tree.");
        }
        return new GitRepository(Path.of(result.stdout().trim()).toAbsolutePath().normalize());
    }

    /**
     * Returns the repository root.
     *
     * @return absolute repository root
     */
    public Path root() {
        return root;
    }

    /**
     * Returns a human-readable repository name.
     *
     * @return remote repository name when available, otherwise root directory name
     */
    public String repositoryName() {
        String remote = output(List.of("git", "remote", "get-url", "origin")).orElse("");
        String normalized = remote.trim().replace('\n', ' ');
        if (!normalized.isBlank()) {
            int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf(':'));
            String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
        }
        return root.getFileName() == null ? "repository" : root.getFileName().toString();
    }

    /**
     * Returns the current branch, or a detached-head label.
     *
     * @return branch name
     */
    public String currentBranch() {
        return output(List.of("git", "symbolic-ref", "--quiet", "--short", "HEAD"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> output(List.of("git", "rev-parse", "--short", "HEAD"))
                        .map(value -> "(detached at " + value.trim() + ")")
                        .orElse("(unknown)"));
    }

    /**
     * Resolves a Git revision to a full commit SHA.
     *
     * @param ref branch, tag, or commit expression
     * @return full commit SHA
     */
    public String resolveCommit(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new CliFailure(CliExitCode.GIT_RESOLUTION_FAILURE, "Git revision must not be blank");
        }
        CommandResult result = run(root, List.of("git", "rev-parse", "--verify", ref + "^{commit}"));
        if (result.exitCode() != 0 || result.stdout().isBlank()) {
            throw new CliFailure(CliExitCode.GIT_RESOLUTION_FAILURE,
                    "Could not resolve Git revision '" + ref + "'. Fetch it or pass a valid --base value.");
        }
        return result.stdout().trim();
    }

    /**
     * Resolves the default base branch using the documented precedence.
     *
     * @param explicitBase optional command-line base
     * @return selected ref and resolved commit
     */
    public ResolvedRef resolveBase(String explicitBase) {
        List<String> candidates = new ArrayList<>();
        if (explicitBase != null && !explicitBase.isBlank()) {
            candidates.add(explicitBase);
        } else {
            String githubBase = System.getenv("GITHUB_BASE_REF");
            if (githubBase != null && !githubBase.isBlank()) {
                candidates.add("origin/" + githubBase);
                candidates.add(githubBase);
            }
            output(List.of("git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .ifPresent(candidates::add);
            candidates.add("main");
            candidates.add("master");
            candidates.add("origin/main");
            candidates.add("origin/master");
        }
        for (String candidate : candidates) {
            try {
                return new ResolvedRef(candidate, resolveCommit(candidate));
            } catch (CliFailure ignored) {
                // Continue through the documented fallback chain.
            }
        }
        String attempted = String.join(", ", candidates);
        throw new CliFailure(CliExitCode.GIT_RESOLUTION_FAILURE,
                "Could not resolve a base branch. Tried: " + attempted
                        + ". Pass --base <branch-or-commit> or fetch the repository's default branch.");
    }

    /**
     * Resolves the candidate commit.
     *
     * @param candidate optional revision, defaulting to HEAD
     * @return selected ref and resolved commit
     */
    public ResolvedRef resolveCandidate(String candidate) {
        String selected = candidate == null || candidate.isBlank() ? "HEAD" : candidate;
        return new ResolvedRef(selected, resolveCommit(selected));
    }

    /**
     * Returns whether the working tree contains changes under a path.
     *
     * @param relativePath repository-relative path
     * @return true when tracked or untracked changes are present
     */
    public boolean hasWorkingTreeChanges(Path relativePath) {
        String path = relativePath.toString().replace('\n', '_');
        return output(List.of("git", "status", "--porcelain=v1", "--untracked-files=all", "--", path))
                .map(value -> !value.isBlank())
                .orElse(false);
    }

    /**
     * Creates a detached worktree for a resolved commit.
     *
     * @param destination empty destination path
     * @param commit commit SHA or ref
     */
    public void addWorktree(Path destination, String commit) {
        CommandResult result = run(root, List.of("git", "worktree", "add", "--detach", "--quiet",
                destination.toAbsolutePath().toString(), commit));
        if (result.exitCode() != 0) {
            throw new CliFailure(CliExitCode.GIT_RESOLUTION_FAILURE,
                    "Could not materialize Git worktree for " + commit + ": " + message(result));
        }
    }

    /**
     * Removes a detached worktree without touching the active checkout.
     *
     * @param destination worktree path
     */
    public void removeWorktree(Path destination) {
        if (destination == null || !Files.exists(destination)) {
            return;
        }
        run(root, List.of("git", "worktree", "remove", "--force", destination.toAbsolutePath().toString()));
    }

    private Optional<String> output(List<String> command) {
        CommandResult result = run(root, command);
        return result.exitCode() == 0 ? Optional.of(result.stdout()) : Optional.empty();
    }

    private static CommandResult run(Path directory, List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).start();
            byte[] stdout = readBounded(process.getInputStream());
            byte[] stderr = readBounded(process.getErrorStream());
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new CliFailure(CliExitCode.GIT_RESOLUTION_FAILURE,
                        "Git command timed out: " + String.join(" ", command));
            }
            return new CommandResult(process.exitValue(), text(stdout), text(stderr));
        } catch (CliFailure failure) {
            throw failure;
        } catch (IOException exception) {
            throw new CliFailure(CliExitCode.GIT_RESOLUTION_FAILURE,
                    "Git is required but could not be executed. Install Git and retry.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CliFailure(CliExitCode.GIT_RESOLUTION_FAILURE, "Git command was interrupted", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static byte[] readBounded(InputStream stream) throws IOException {
        try (stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (output.size() > MAX_OUTPUT_BYTES) {
                    throw new IOException("Git output exceeded the safety limit");
                }
            }
            return output.toByteArray();
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static String message(CommandResult result) {
        String stderr = result.stderr().trim();
        return stderr.isBlank() ? result.stdout().trim() : stderr;
    }

    /**
     * A resolved Git ref.
     *
     * @param ref selected ref
     * @param commit full commit SHA
     * @author Shelton Bumhe
     */
    public record ResolvedRef(String ref, String commit) {
        /** @return seven-character display SHA */
        public String shortCommit() {
            return commit.substring(0, Math.min(7, commit.length())).toLowerCase(Locale.ROOT);
        }
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {}
}
