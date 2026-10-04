package com.portfolio.mdslab.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.portfolio.mdslab.AbstractMySqlIntegrationTest;
import com.portfolio.mdslab.domain.Album;
import com.portfolio.mdslab.domain.SettlementTargetType;
import com.portfolio.mdslab.repository.AlbumRepository;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * facts/projects/mds-global-distribution.md 1번 항목("정산 중 메타데이터 수정
 * 차단을 위한 경량 락 설계")을 재현한다. Before(가드 없음 — 문제)와 After(가드
 * 있음 — 해결), 그리고 F-5(TTL 기반 자동 복구)를 각각 증명한다.
 */
@DisplayName("정산 중 메타데이터 수정 차단 — 상태 플래그 기반 경량 락 + AOP 가드")
class SettlementGuardTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private AlbumMetadataService albumMetadataService;

    @Autowired
    private SettlementBatchService settlementBatchService;

    private Long albumId;

    @BeforeEach
    void setUp() {
        Album album = albumRepository.save(new Album("Midnight Echoes"));
        albumId = album.getId();
    }

    @Test
    @DisplayName("Before(문제): 가드가 없으면 정산 중에도 메타데이터 수정이 그대로 반영된다")
    void withoutGuard_updateSucceedsEvenDuringSettlement() {
        boolean acquired = settlementBatchService.acquireLock(
                SettlementTargetType.ALBUM, albumId, "batch-run-1", Duration.ofMinutes(15));
        assertThat(acquired).isTrue();

        Album updated = albumMetadataService.updateMetadataUnguarded(albumId, "Midnight Echoes (Deluxe)");

        assertThat(updated.getTitle())
                .as("가드가 없으니 정산 중이어도 수정 요청이 그대로 반영된다 — 이것이 막아야 할 문제다")
                .isEqualTo("Midnight Echoes (Deluxe)");
    }

    @Test
    @DisplayName("After(해결): 가드가 있으면 정산 중 메타데이터 수정 요청이 차단된다")
    void withGuard_updateIsBlockedDuringSettlement() {
        boolean acquired = settlementBatchService.acquireLock(
                SettlementTargetType.ALBUM, albumId, "batch-run-1", Duration.ofMinutes(15));
        assertThat(acquired).isTrue();

        assertThatThrownBy(() -> albumMetadataService.updateMetadataGuarded(albumId, "Midnight Echoes (Deluxe)"))
                .isInstanceOf(SettlementInProgressException.class);

        Album stillOriginal = albumRepository.findById(albumId).orElseThrow();
        assertThat(stillOriginal.getTitle())
                .as("차단됐으니 제목은 바뀌지 않아야 한다")
                .isEqualTo("Midnight Echoes");
    }

    @Test
    @DisplayName("After(해결): 정산이 종료되면(락 해제) 다시 수정이 허용된다")
    void withGuard_updateIsAllowedAfterSettlementReleased() {
        settlementBatchService.acquireLock(
                SettlementTargetType.ALBUM, albumId, "batch-run-1", Duration.ofMinutes(15));
        assertThatThrownBy(() -> albumMetadataService.updateMetadataGuarded(albumId, "should fail"))
                .isInstanceOf(SettlementInProgressException.class);

        boolean released = settlementBatchService.releaseLock(SettlementTargetType.ALBUM, albumId, "batch-run-1");
        assertThat(released).isTrue();

        Album updated = assertDoesNotThrow(
                () -> albumMetadataService.updateMetadataGuarded(albumId, "Midnight Echoes (Deluxe)"));
        assertThat(updated.getTitle()).isEqualTo("Midnight Echoes (Deluxe)");
    }

    @Test
    @DisplayName("F-5: 정산 배치가 release를 호출하지 못하고 죽어도(TTL 미해제), TTL 경과 후에는 자동으로 다시 허용된다")
    void ttlAutoRecoversLockAfterAbnormalTermination() {
        // release를 절대 호출하지 않는다 — "정산 배치가 비정상 종료했다"는 상황을 흉내낸다.
        settlementBatchService.acquireLock(
                SettlementTargetType.ALBUM, albumId, "batch-run-crashed", Duration.ofSeconds(2));

        assertThatThrownBy(() -> albumMetadataService.updateMetadataGuarded(albumId, "still locked"))
                .as("TTL 만료 전에는 여전히 차단되어야 한다")
                .isInstanceOf(SettlementInProgressException.class);

        await().atMost(Duration.ofSeconds(6))
                .untilAsserted(() -> assertDoesNotThrow(() ->
                        albumMetadataService.updateMetadataGuarded(albumId, "Midnight Echoes (Deluxe)")));

        Album updated = albumRepository.findById(albumId).orElseThrow();
        assertThat(updated.getTitle())
                .as("TTL 경과 후 자동 복구되어 release 없이도 수정이 반영되어야 한다")
                .isEqualTo("Midnight Echoes (Deluxe)");
    }
}
