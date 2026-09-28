package com.portfolio.equitylab.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [NEW-DESIGN] 이 랩을 위해 새로 정의한 엔티티. FLO의 실제 컬럼명이 아니다.
 *
 * [FACT] "유통사ID+기간+track_id 조합은 유니크하지만, 실제 PK는
 * auto-increment였고 쿼리에서는 PK를 활용하지 않았다"는 점만
 * facts/projects/equity-system.md(2026-09-27)에서 확인된 사실이다 — 그래서
 * id는 순수 대체키(surrogate key)로만 두고, 삭제 쿼리들은 전부
 * distributor_code + start_date/end_date 조합만 사용한다(PK는 쓰지 않음).
 */
@Entity
@Table(name = "equity_share")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquityShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "distributor_code", nullable = false, length = 20)
    private String distributorCode;

    @Column(name = "track_id", nullable = false)
    private Long trackId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "share_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal sharePercentage;

    public EquityShare(String distributorCode, Long trackId, LocalDate startDate, LocalDate endDate,
                        BigDecimal sharePercentage) {
        this.distributorCode = distributorCode;
        this.trackId = trackId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.sharePercentage = sharePercentage;
    }
}
