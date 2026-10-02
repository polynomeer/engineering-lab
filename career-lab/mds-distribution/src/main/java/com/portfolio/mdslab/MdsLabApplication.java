package com.portfolio.mdslab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * [NEW-DESIGN] 랩 전체를 부팅하기 위한 최소 엔트리포인트.
 * equity-system-lab과 동일하게 REST 컨트롤러는 두지 않는다 — 이 포트폴리오의
 * 목적은 "동시성 제어·이벤트 파이프라인 설계를 테스트로 증명"하는 것이라
 * API 계층은 시연 목표에 필요하지 않다.
 */
@SpringBootApplication
public class MdsLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(MdsLabApplication.class, args);
    }
}
