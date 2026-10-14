package com.pnomeer.lab.experiments.career;

import com.pnomeer.lab.registry.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import java.util.*;

@Configuration
@ConditionalOnExpression("${lab.career.sequence.enabled:false} or ${lab.career.checks.enabled:false}")
public class McpModuleConfiguration {
    @Bean public ExperimentModule mcpModule(@Value("${lab.career.sequence.enabled:false}") boolean sequence,
            @Value("${lab.career.checks.enabled:false}") boolean checks) {
        return new ExperimentModule() {
            public ModuleDescriptor module() { return new ModuleDescriptor("mcp-platform", "MCP platform", "career"); }
            public List<ExperimentDescriptor> experiments() { var descriptors = new ArrayList<ExperimentDescriptor>();
                if (sequence) descriptors.add(SequenceRegistrationConfiguration.DESCRIPTOR);
                if (checks) descriptors.addAll(List.of(ChecksRegistrationConfiguration.ARCHITECTURE, ChecksRegistrationConfiguration.VALIDATION));
                return List.copyOf(descriptors); }
            public List<com.pnomeer.lab.metrics.MetricCatalog.Definition> metrics() {
                if (!sequence) return List.of();
                return List.of(metric("sequence.elapsedNanos", "ns", "Harness duration; excludes startup; BEFORE includes a synthetic 50ms delay"),
                        metric("sequence.overlapPairs", "pairs", "Overlapping allocation pairs; BEFORE expects an anomaly"),
                        metric("sequence.finalValue", "codes", "Final database sequence value"));
            }
            private com.pnomeer.lab.metrics.MetricCatalog.Definition metric(String name, String unit, String meaning) {
                return new com.pnomeer.lab.metrics.MetricCatalog.Definition(name, unit, "worker-run", "RUN_FINAL", meaning + "; use the final value, never sum snapshots");
            }
        };
    }
}
