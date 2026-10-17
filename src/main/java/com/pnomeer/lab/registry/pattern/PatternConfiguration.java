package com.pnomeer.lab.registry.pattern;

import com.pnomeer.lab.registry.ExperimentCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class PatternConfiguration {
    @Bean public com.pnomeer.lab.registry.measurement.MeasurementContribution builtInMeasurements() {
        return com.pnomeer.lab.registry.measurement.BuiltInMeasurements.contribution();
    }
    @Bean public com.pnomeer.lab.registry.measurement.MeasurementCatalog measurementCatalog(ExperimentCatalog active,
            List<com.pnomeer.lab.registry.measurement.MeasurementContribution> contributions) {
        return new com.pnomeer.lab.registry.measurement.MeasurementCatalog(active, contributions);
    }
    @Bean public StudyContribution builtInStudies() { return BuiltInStudies.contribution(); }
    @Bean public StudyCatalog studyCatalog(ExperimentCatalog active, PatternCatalog patterns, List<StudyContribution> contributions) {
        return new StudyCatalog(active, patterns, contributions);
    }
    @Bean public PatternContribution builtInPatternMetadata() { return BuiltInPatternMetadata.contribution(); }
    @Bean public PatternCatalog patternCatalog(ExperimentCatalog active, List<PatternContribution> contributions) {
        return new PatternCatalog(active, contributions);
    }
}
