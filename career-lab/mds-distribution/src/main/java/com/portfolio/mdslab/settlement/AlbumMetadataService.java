package com.portfolio.mdslab.settlement;

import com.portfolio.mdslab.domain.Album;
import com.portfolio.mdslab.domain.SettlementTargetType;
import com.portfolio.mdslab.repository.AlbumRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앨범 메타데이터 수정 API. {@code updateMetadataUnguarded}와
 * {@code updateMetadataGuarded}는 정확히 같은 수정 로직을 수행하지만, 후자에만
 * {@link SettlementGuarded}가 붙어 있다 — Before/After를 같은 서비스 안에서
 * 대비시켜, "가드 유무"만이 유일한 변수임을 테스트로 보이기 위함이다
 * (equity-system-lab의 Legacy/Safe 대비 패턴과 동일한 의도).
 *
 * 두 메서드 모두 스프링이 관리하는 빈의 공개 메서드이므로, 테스트에서 주입받은
 * 빈을 통해 호출하면(자기 자신을 통한 self-invocation이 아니라) AOP 프록시가
 * 정상적으로 개입한다.
 */
@Service
@RequiredArgsConstructor
public class AlbumMetadataService {

    private final AlbumRepository albumRepository;

    /** [FACT 기반 재현] Before — 가드가 없어 정산 중에도 수정이 그대로 반영된다(문제). */
    @Transactional
    public Album updateMetadataUnguarded(Long albumId, String newTitle) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new IllegalArgumentException("album not found: " + albumId));
        album.updateTitle(newTitle);
        return album;
    }

    /** [FACT 기반 재현] After — AOP 가드가 정산 중 수정 요청을 사전 차단한다(해결). */
    @SettlementGuarded(type = SettlementTargetType.ALBUM, idParam = "albumId")
    @Transactional
    public Album updateMetadataGuarded(Long albumId, String newTitle) {
        Album album = albumRepository.findById(albumId)
                .orElseThrow(() -> new IllegalArgumentException("album not found: " + albumId));
        album.updateTitle(newTitle);
        return album;
    }
}
