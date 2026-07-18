package dev.opaguard.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScalabilityBenchmarkRunnerTest {
    @Test
    void calculatesLinearScalingSlopeNearOne() {
        var points = List.of(
                new ScalabilityBenchmarkRunner.Point(1, 100),
                new ScalabilityBenchmarkRunner.Point(2, 200),
                new ScalabilityBenchmarkRunner.Point(4, 400),
                new ScalabilityBenchmarkRunner.Point(8, 800));

        assertThat(ScalabilityBenchmarkRunner.logLogSlope(points)).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void detectsFlatThroughputAsNoScaling() {
        var points = List.of(
                new ScalabilityBenchmarkRunner.Point(1, 100),
                new ScalabilityBenchmarkRunner.Point(2, 100),
                new ScalabilityBenchmarkRunner.Point(4, 100),
                new ScalabilityBenchmarkRunner.Point(8, 100));

        assertThat(ScalabilityBenchmarkRunner.logLogSlope(points)).isCloseTo(0,
                org.assertj.core.data.Offset.offset(0.0001));
    }
}
