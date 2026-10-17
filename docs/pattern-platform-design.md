# 패턴 중심 실험 플랫폼 설계

2026-10-17. 상태: P1 카탈로그·P2 탐색 구현 완료, P3 이후는 후속 작업이다. 단계별 실제 완료 여부는
[작업 계획](pattern-platform-plan.md)을 따른다. 운영 v0.1.0과 개발 소스는 별개이며,
이 개편이 운영 배포물을 자동 교체하지 않는다.

## 목적과 개념

프로젝트 출처 중심의 engineering/career 탐색에 기술 문제·패턴 중심 탐색을 추가한다.
기존 프로젝트의 업무 맥락과 FACT 기반 재현/NEW-DESIGN 구분은 보존한다.

| 개념 | 책임 |
| --- | --- |
| Category | 탐색용 상위 문제 분야 |
| Pattern | 반복되는 문제, 검증할 성질, 적용 한계 |
| Experiment | 가설을 검증하는 실제 구현과 기존 영속 ID |
| Strategy | 비교할 구현 방식; BEFORE/AFTER의 구체적인 의미 |
| Scenario | 입력·부하·실패 조건·기대 결과 |
| Case study | 프로젝트·업무 맥락·출처와 재현 범위 |
| Run | 실행 당시 조건·환경·관측·CHECK·REPORT의 불변 증거 |

분류와 빌드/배포 경계를 분리한다. 카테고리로 executor를 선택하지 않는다.
Java 25 플랫폼과 Java 21 Career 독립 빌드, worker wire protocol을 유지한다.
같은 패턴에 속한다는 이유만으로 같은 구현·단위·관측 범위라고 판단하지 않는다.

## 초기 분류와 16개 실험 지도

대표 category는 하나, pattern은 여러 개로 연결한다. 아래 목록은 새 실험을 제한하는
폐쇄 enum이 아니다. 신뢰된 서버 등록 코드가 정의를 추가할 수 있다.

| category ID | 분야 | 기존 experiment ID | 핵심 패턴 |
| --- | --- | --- | --- |
| data-processing | 데이터 처리와 자원 관리 | pipeline.excel-ingestion | staged-pipeline, bounded-queue, chunked-write |
| concurrency | 동시성과 조정 | concurrency.queue-contention | bounded-queue |
| io-execution | I/O와 실행 모델 | io.endpoint-comparison | io-execution-model |
| concurrency | 동시성과 조정 | career.mcp-platform.sequence | atomic-update, mutual-exclusion |
| architecture-validation | 아키텍처와 검증 | career.mcp-platform.architecture | dependency-rules |
| architecture-validation | 아키텍처와 검증 | career.mcp-platform.validation | layered-validation |
| persistence | 데이터 저장과 트랜잭션 | career.equity-system.deletion | indexed-delete, transaction-chunking |
| concurrency | 동시성과 조정 | career.equity-system.redis-lock | mutual-exclusion |
| data-processing | 데이터 처리와 자원 관리 | career.equity-system.registration | staged-pipeline, bounded-parallelism, bounded-queue, chunked-write |
| data-processing | 데이터 처리와 자원 관리 | career.batch-excel.batch-memory | heap-working-set, chunked-write |
| data-processing | 데이터 처리와 자원 관리 | career.batch-excel.excel-memory | heap-working-set, streaming |
| concurrency | 동시성과 조정 | career.mds-distribution.settlement-guard | guarded-state-transition |
| reliability | 신뢰성과 실패 처리 | career.mds-distribution.idempotency | idempotent-processing |
| security-session | 보안과 세션 상태 | career.creator-studio.session-rotation | session-rotation, atomic-update |
| persistence | 데이터 저장과 트랜잭션 | career.creator-studio.mapping-integrity | scoped-transfer, guarded-state-transition |
| reliability | 신뢰성과 실패 처리 | career.creator-studio.post-commit | post-commit-delivery |

이 연결은 학습·탐색 관계다. 분류만으로 특정 패턴의 모든 보장을 구현했다고 주장하지 않는다.
예를 들어 Redis 소유권/TTL CHECK는 모든 경쟁 조건의 증명이 아니고, MDS의 합성
멱등성 검증은 실제 메시지 broker·ACK·프로세스 crash의 exactly-once 보장이 아니다.
삭제 단계 시간은 실제 잠금 점유 시간의 직접 측정이 아니며, heap 표본은 RSS가 아니다.

## P1: 실행과 분리된 카탈로그

`registry.pattern` 아래 불변 metadata와 검증 catalog를 둔다. Spring의
`PatternContribution` bean 목록을 합치므로 새 분야·패턴·실험 연결은 중앙 controller의
분기 없이 추가한다. 최초 16개 연결은 `BuiltInPatternMetadata`에 명시한다.
분류 데이터의 모듈별 소유권 분산은 후속 변경으로 진행할 수 있다.

새 `GET /dashboard/v1/pattern-catalog`만 추가한다. 응답은 다음을 포함한다.

- `schemaVersion: 1`: 응답 구조 버전.
- `categories`: id/title, `patterns`: id/categoryId/title/problem/verification/limitations.
- `experiments`: 현재 활성 catalog의 experimentId/sourceModuleId/sourceCollection과
  `classification`(categoryId/patternIds), 미분류는 null.

정의 사전에는 비활성 실험과 연관된 패턴도 남을 수 있다. `experiments`에는 실제 등록된
실험만 노출한다. 이 API는 readiness를 판정하지 않으며 기존 dashboard readiness API를
계속 사용한다. 브라우저가 입력한 임의 분류나 실행 명령을 받아 실행하지 않는다.

중복 ID·중복 연결·없는 category/pattern 참조·잘못된 문자열·크기 상한 초과는 시작 시
거부한다. 미분류 실험은 허용하여 기존 provider와 새 모듈의 호환성을 유지한다.
대표 category는 연결된 pattern 중 하나의 category와 일치해야 한다.
응답은 ID 순서로 정렬하고 변경 불가능한 목록을 사용한다. metadata에는 정의별 길이와
전체 항목 수 상한을 두며 Run·메트릭을 메모리에 모으지 않는다.

## 호환성과 버전 정책

- 기존 `/experiments`, `/modules`, `/runs`, descriptor의 category/collection과 capability
  응답은 변경하지 않는다. 새 frontend는 새 endpoint의 404에서 기존 탐색으로 fallback한다(P2).
- 기존 experiment/module/scenario/metric ID, URL, worker manifest를 바꾸지 않는다.
- DB schema, Run metadata, scenario canonical JSON/hash, CHECK/export 형식을 변경하지 않는다.
- 분류는 현재 catalog의 해석이다. 과거 Run에 소급 기록하거나 과거 분류였다고 표시하지 않는다.
- 패턴 ID는 의미가 유지되는 동안 고정한다. 분류 재배치는 현재 탐색에만 반영한다.
  의미가 달라진 패턴은 새 ID로 추가하고 폐기/alias 정책은 P3에서 설계한다.
- 향후 실행 시점의 패턴/전략 정보를 저장하려면 별도 버전의 optional snapshot을 설계하고,
  snapshot 없는 구형 Run을 지원해야 한다. P1에서는 저장하지 않는다.
- 신규 API를 제거하면 기존 동작으로 돌아갈 수 있도록 첫 단계는 읽기 전용으로 제한한다.

## 후속 공통화 방향

Excel ingestion과 Equity 등록을 첫 비교 대상으로 삼아 입력→검증→변환→청크 저장의
공통 계약을 도출한다. bounded queue와 bounded in-flight window는 서로 다른 구현이며
동일 모델로 강제 변환하지 않는다. parser·업무 규칙·저장 adapter는 사례별로 보존한다.
JDK 경계를 넘는 공통화를 위해 Java 25 core에 Java 21 모듈을 직접 결합하지 않는다.

실험 비교에는 단위, metric 의미, 측정 구간, 입력 규모/seed, JVM/DB 환경, 실패 조건을
명시한다. 동일 pattern ID만으로 성능 순위를 만들지 않는다. 트랜잭션 범위·재시도·DLQ·
종료 drain·메모리 상한을 유지한 공통화만 수용한다.

## 검증과 배포

P1은 기본 3개와 전체 16개 catalog, 미분류 확장, 중복/참조/상한 실패, 신규 HTTP 응답,
기존 API·scenario·Run 재개방/내보내기 회귀를 검증한다. 루트 전체 `./gradlew test`가
필수다. Career 소스/빌드를 변경하는 후속 단계에서는 Career 전체 테스트를 먼저 실행하고
루트 wrapper를 순차 실행한다. UI 단계는 실제 브라우저·기존 URL·fallback 검증을 포함한다.
운영 전환은 최종 단계에서 백업·별도 포트 인수·재시작 후 증거 비교를 거쳐 수행한다.
