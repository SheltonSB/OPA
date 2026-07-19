package dev.opaguard.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises Git ref detection and detached worktree lifecycle in isolated repositories.
 *
 * @author Shelton Bumhe
 */
class GitRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void explicitBaseAndHeadResolveToCommits() throws Exception {
        initRepository();
        commit("baseline");
        git("switch", "-c", "feature");
        Files.writeString(tempDir.resolve("policy.rego"), "package authz\nallow := true\n");
        commit("candidate");

        GitRepository repository = GitRepository.discover(tempDir);
        GitRepository.ResolvedRef base = repository.resolveBase("main");
        GitRepository.ResolvedRef candidate = repository.resolveCandidate("HEAD");

        assertThat(base.commit()).hasSize(40);
        assertThat(candidate.commit()).hasSize(40);
        assertThat(repository.currentBranch()).isEqualTo("feature");
        assertThat(base.commit()).isNotEqualTo(candidate.commit());
    }

    @Test
    void fallsBackToMainAndMasterAndReportsMissingBase() throws Exception {
        initRepository();
        commit("baseline");
        GitRepository repository = GitRepository.discover(tempDir);
        assertThat(repository.resolveBase(null).ref()).isEqualTo("main");

        assertThatThrownBy(() -> repository.resolveBase("does-not-exist"))
                .isInstanceOf(CliFailure.class)
                .extracting("exitCode")
                .isEqualTo(CliExitCode.GIT_RESOLUTION_FAILURE);
    }

    @Test
    void originHeadIsPreferredBeforeMainFallback() throws Exception {
        initRepository();
        commit("baseline");
        git("branch", "release");
        git("update-ref", "refs/remotes/origin/release", "HEAD");
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/release");
        GitRepository repository = GitRepository.discover(tempDir);

        assertThat(repository.resolveBase(null).ref()).isEqualTo("origin/release");
    }

    @Test
    void detectsUncommittedChangesAndCleansWorktree() throws Exception {
        initRepository();
        commit("baseline");
        Files.writeString(tempDir.resolve("policy.rego"), "package authz\nallow := true\n");
        GitRepository repository = GitRepository.discover(tempDir);
        assertThat(repository.hasWorkingTreeChanges(Path.of("policy.rego"))).isTrue();

        Path worktree = tempDir.resolveSibling("opa-guard-test-worktree-" + System.nanoTime());
        repository.addWorktree(worktree, repository.resolveCandidate("HEAD").commit());
        assertThat(Files.isDirectory(worktree)).isTrue();
        repository.removeWorktree(worktree);
        assertThat(Files.exists(worktree)).isFalse();
    }

    private void initRepository() throws Exception {
        git("init", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "OPA Guard Test");
    }

    private void commit(String message) throws Exception {
        Files.writeString(tempDir.resolve("README.md"), message + "\n");
        git("add", ".");
        git("commit", "-m", message);
    }

    private void git(String... arguments) throws Exception {
        Process process = new ProcessBuilder(List.of(concat(new String[]{"git"}, arguments)))
                .directory(tempDir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("git command failed: " + output);
        }
    }

    private static String[] concat(String[] first, String[] second) {
        String[] result = new String[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
