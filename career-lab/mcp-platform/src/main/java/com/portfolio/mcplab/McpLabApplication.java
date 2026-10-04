package com.portfolio.mcplab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * [NEW-DESIGN] 진입점. FLO(드림어스컴퍼니) MCP(음원 콘텐츠 플랫폼) 전면 개편 경험 중
 * facts/projects/mcp-platform-revamp.md에서 확정된 두 가지를 재현한다.
 *
 * <p>1. 계약 코드 채번의 다중 인스턴스 range allocation 동시성 버그와 그 수정
 * ({@link com.portfolio.mcplab.sequence} 패키지) — 가장 핵심적인 재현 대상.</p>
 * <p>2. ArchUnit 기반 아키텍처 가드레일 ({@code src/test/.../archrule} 패키지).</p>
 *
 * <p>회사 코드베이스와 무관하게 새로 만든 독립 프로젝트이며, 도메인 이름(계약/앨범 등)만
 * 원 프로젝트 맥락을 빌렸을 뿐 실제 FLO 코드는 전혀 포함하지 않는다.</p>
 */
@SpringBootApplication
public class McpLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpLabApplication.class, args);
    }
}
