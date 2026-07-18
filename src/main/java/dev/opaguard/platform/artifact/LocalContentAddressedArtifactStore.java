package dev.opaguard.platform.artifact;

import dev.opaguard.exception.GuardException;
import dev.opaguard.platform.port.ArtifactStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Resolves tenant-confined local artifacts and verifies their SHA-256 identity.
 *
 * <p>Object keys are catalog issued, paths are normalized and checked again after
 * real-path resolution, symlinks are rejected, and file/byte ceilings limit DoS risk.</p>
 *
 * @author Shelton Bumhe
 */
@Component
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "worker")
public class LocalContentAddressedArtifactStore implements ArtifactStore {
    private static final long MAX_POLICY_BYTES = 256L * 1024 * 1024;
    private static final int MAX_POLICY_FILES = 10_000;
    private static final long MAX_DATASET_BYTES = 64L * 1024 * 1024;
    private final Path root;

    public LocalContentAddressedArtifactStore(@Value("${opa-guard.artifacts.root:/var/lib/opa-guard/artifacts}") Path root) {
        try {
            this.root = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new GuardException("Artifact root is unavailable", exception);
        }
    }

    @Override
    public Path resolvePolicy(UUID organizationId, String objectKey, String expectedSha256) {
        Path path = resolve(organizationId, objectKey);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new GuardException("Policy artifact is not a directory");
        }
        verifyTree(path, expectedSha256);
        return path;
    }

    @Override
    public Path resolveDataset(UUID organizationId, String objectKey, String expectedSha256) {
        Path path = resolve(organizationId, objectKey);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new GuardException("Dataset artifact is not a regular file");
        }
        try {
            if (Files.size(path) > MAX_DATASET_BYTES) throw new GuardException("Dataset artifact exceeds 64 MiB");
        } catch (IOException exception) {
            throw new GuardException("Unable to inspect dataset artifact", exception);
        }
        verifyFile(path, expectedSha256);
        return path;
    }

    private Path resolve(UUID organizationId, String objectKey) {
        if (objectKey == null || !objectKey.matches("[a-f0-9]{64}(?:/[A-Za-z0-9._-]{1,128})?")) {
            throw new GuardException("Artifact key is not a valid content-addressed key");
        }
        try {
            Path tenantRoot = root.resolve(organizationId.toString()).normalize();
            Path candidate = tenantRoot.resolve(objectKey).normalize();
            if (!candidate.startsWith(tenantRoot)) throw new GuardException("Artifact path escaped the tenant root");
            Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(tenantRoot) || Files.isSymbolicLink(real)) {
                throw new GuardException("Artifact path is not confined to the tenant root");
            }
            return real;
        } catch (IOException exception) {
            throw new GuardException("Artifact is unavailable", exception);
        }
    }

    private void verifyTree(Path directory, String expected) {
        MessageDigest digest = sha256();
        long totalBytes = 0;
        int fileCount = 0;
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.comparing(path -> directory.relativize(path).toString())).toList()) {
                if (Files.isSymbolicLink(file)) throw new GuardException("Artifact tree contains a symbolic link");
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    fileCount++;
                    totalBytes = Math.addExact(totalBytes, Files.size(file));
                    if (fileCount > MAX_POLICY_FILES || totalBytes > MAX_POLICY_BYTES) {
                        throw new GuardException("Policy artifact exceeds file-count or byte limits");
                    }
                    String relative = directory.relativize(file).toString().replace('\\', '/');
                    digest.update(relative.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    updateDigest(digest, file);
                }
            }
        } catch (IOException exception) {
            throw new GuardException("Unable to verify policy artifact", exception);
        }
        requireDigest(digest, expected);
    }

    private void verifyFile(Path file, String expected) {
        MessageDigest digest = sha256();
        updateDigest(digest, file);
        requireDigest(digest, expected);
    }

    private static void updateDigest(MessageDigest digest, Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        } catch (IOException exception) {
            throw new GuardException("Unable to hash artifact", exception);
        }
    }

    private static void requireDigest(MessageDigest digest, String expected) {
        if (expected == null || !expected.matches("[a-f0-9]{64}")) throw new GuardException("Invalid expected artifact digest");
        byte[] expectedBytes = HexFormat.of().parseHex(expected);
        if (!MessageDigest.isEqual(digest.digest(), expectedBytes)) {
            throw new GuardException("Artifact integrity verification failed");
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
