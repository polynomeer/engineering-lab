package com.portfolio.mdslab.messaging;

import com.portfolio.mdslab.domain.Album;
import com.portfolio.mdslab.repository.AlbumRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FACT 기반 재현] design 4.1절 "등록 성공 시 DB 저장" — 멱등 체크를 통과한
 * 메시지에 대한 실제 등록 처리 로직. 실제 FLO의 등록 처리는 파트너/계약
 * 검증 등을 포함하지만, 이 랩은 "멱등 처리가 실제 처리 로직의 실행 횟수를
 * 제어하는가"만 증명하면 되므로 앨범 저장 한 단계로 축소했다.
 */
@Service
@RequiredArgsConstructor
public class AlbumRegistrationService {

    private final AlbumRepository albumRepository;

    @Transactional
    public void process(AlbumRegisteredEvent event) {
        if (AlbumRegisteredEvent.FORCE_FAIL_TITLE.equals(event.albumTitle())) {
            // [신규 결정] 테스트 훅 — 처리 실패를 결정론적으로 재현하기 위함(실제 FLO 로직 아님).
            throw new IllegalStateException("의도적으로 실패시킨 메시지: " + event.messageId());
        }
        albumRepository.save(new Album(event.albumTitle()));
    }
}
