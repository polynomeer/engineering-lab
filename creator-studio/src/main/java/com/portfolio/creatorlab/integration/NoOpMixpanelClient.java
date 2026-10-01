package com.portfolio.creatorlab.integration;

import org.springframework.stereotype.Component;

/** [NEW-DESIGN] NoOpBrazeClient와 동일한 이유. */
@Component
public class NoOpMixpanelClient implements MixpanelClient {
    @Override
    public void track(ContentChangedEvent event) {
        // no-op
    }
}
