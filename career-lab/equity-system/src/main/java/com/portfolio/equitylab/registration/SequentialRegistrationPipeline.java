package com.portfolio.equitylab.registration;

import com.portfolio.equitylab.registration.RowProcessor.MappedEquityRow;
import com.portfolio.equitylab.repository.EquityShareInsertDao;
import com.pnomeer.lab.pipeline.BoundedBatch;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * [FACT 기반 재현] "Before" — facts/projects/equity-system.md 3번 항목의
 * Problem: "Excel 데이터 파싱→DB 저장을 단일 스레드에서 순차 처리".
 */
@Service
public class SequentialRegistrationPipeline {

    /** [NEW-DESIGN] 삽입 배치 크기. */
    static final int INSERT_BATCH_SIZE = 1_000;

    private final RowProcessor rowProcessor;
    private final EquityShareInsertDao insertDao;

    public SequentialRegistrationPipeline(RowProcessor rowProcessor, EquityShareInsertDao insertDao) {
        this.rowProcessor = rowProcessor;
        this.insertDao = insertDao;
    }

    public int register(List<RawEquityRow> rawRows) {
        var batch = new BoundedBatch<MappedEquityRow>(INSERT_BATCH_SIZE);
        int total = 0;
        for (RawEquityRow raw : rawRows) {
            batch.add(rowProcessor.process(raw));
            if (batch.isFull()) total += batch.flushTo(insertDao::insertBatch);
        }
        total += batch.flushTo(insertDao::insertBatch);
        return total;
    }
}
