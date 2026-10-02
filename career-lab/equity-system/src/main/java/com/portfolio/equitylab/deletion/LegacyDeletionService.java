package com.portfolio.equitylab.deletion;

import com.portfolio.equitylab.repository.EquityShareDeletionDao;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [FACT 기반 재현] "Before" — facts/projects/equity-system.md 1번 항목의
 * Problem: 복합 인덱스가 없는 상태에서 대상 조건에 맞는 모든 row를 단일
 * 장기 트랜잭션으로 삭제한다. 협회·대형 유통사처럼 절대다수 row를 가진
 * 대상을 조회하면 인덱스가 없어 사실상 테이블 전체를 스캔하게 되고, 그
 * 스캔·삭제가 끝날 때까지 하나의 트랜잭션·락이 유지된다.
 */
@Service
public class LegacyDeletionService {

    private final EquityShareDeletionDao dao;

    public LegacyDeletionService(EquityShareDeletionDao dao) {
        this.dao = dao;
    }

    @Transactional
    public int deleteAll(String distributorCode, LocalDate periodStart, LocalDate periodEnd) {
        return dao.deleteOverlapping(distributorCode, periodStart, periodEnd);
    }
}
