package com.pnomeer.lab.registry.pattern;

import com.pnomeer.lab.registry.ExperimentCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Configuration
public class PatternConfiguration {
    @Bean public PatternContribution builtInPatternMetadata() { return BuiltInPatternMetadata.contribution(); }
    @Bean public PatternCatalog patternCatalog(ExperimentCatalog active, List<PatternContribution> contributions) {
        return new PatternCatalog(active, contributions);
    }
}
