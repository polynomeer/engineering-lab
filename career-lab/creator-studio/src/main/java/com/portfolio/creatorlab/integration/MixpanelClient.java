package com.portfolio.creatorlab.integration;

/** [NEW-DESIGN] BrazeClient와 동일한 이유로 인터페이스로 추상화한다. */
public interface MixpanelClient {
    void track(ContentChangedEvent event);
}
