package dev.opaguard.integration;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OpaContainerIntegrationTest {
    @Container
    static final GenericContainer<?> OPA = new GenericContainer<>("openpolicyagent/opa:1.16.2-static")
            .withCopyFileToContainer(MountableFile.forClasspathResource("integration/authz.rego"), "/policy/authz.rego")
            .withCommand("run", "--server", "--addr=0.0.0.0:8181", "/policy");

    @Test
    void evaluatesPolicyWithRealOpaRuntime() throws Exception {
        var result = OPA.execInContainer(
                "/opa", "eval", "--format=json", "--data", "/policy",
                "--stdin-input", "data.authz.allow");

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("\"value\": true");
    }
}
