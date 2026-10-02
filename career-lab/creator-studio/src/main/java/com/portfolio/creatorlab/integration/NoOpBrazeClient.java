package com.portfolio.creatorlab.integration;

import org.springframework.stereotype.Component;

/**
 * [NEW-DESIGN] 실제 Braze 계약이 없는 독립 랩이므로 기본 구현은 아무 일도 하지 않는다.
 * 테스트에서는 호출 여부를 기록하는 테스트 더블로 교체한다(NFR-6).
 */
@Component
public class NoOpBrazeClient implements BrazeClient {
    @Override
    public void track(ContentChangedEvent event) {
        // no-op — 실제 서비스 연동은 이 랩의 범위 밖.
    }
}
