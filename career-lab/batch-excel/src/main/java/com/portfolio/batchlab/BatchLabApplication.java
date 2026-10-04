package com.portfolio.batchlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * [NEW-DESIGN] 애플리케이션 진입점. 이 랩은 REST API 없이 배치 Job과 Excel 생성
 * 서비스를 JUnit 벤치마크 테스트로 직접 구동한다(equity-system-lab과 동일한 구성).
 */
@SpringBootApplication
public class BatchLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchLabApplication.class, args);
    }
}
