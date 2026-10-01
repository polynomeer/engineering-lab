package com.portfolio.equitylab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 포트폴리오 재구현 랩 — FLO 지분율 시스템의 확정된 Problem/Decision만
 * 최소 단위로 재현한다. 어떤 부분이 facts 근거이고 어떤 부분이 이 랩을 위해
 * 새로 정한 값/구조인지는 각 클래스 상단 주석과 README.md를 참고할 것.
 */
@SpringBootApplication
public class EquityLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(EquityLabApplication.class, args);
    }
}
