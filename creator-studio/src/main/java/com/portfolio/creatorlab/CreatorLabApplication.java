package com.portfolio.creatorlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 포트폴리오 재구현 랩 — FLO 크리에이터 스튜디오에서 겪은 문제 중 확정된 사실만
 * 골라 독립 프로젝트로 재구현한다. 어떤 부분이 facts 근거([FACT 기반 재현])이고
 * 어떤 부분이 이 랩을 위해 새로 정한 값/구조([NEW-DESIGN])인지는 각 클래스 상단
 * 주석과 README.md를 참고할 것.
 *
 * 원본 사실: career-hub/facts/projects/creator-studio.md
 * 구현 설계: career-hub/design/creator-studio.md
 */
@SpringBootApplication
@EnableAsync
@EnableRetry
public class CreatorLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(CreatorLabApplication.class, args);
    }
}
