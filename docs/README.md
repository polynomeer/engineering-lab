# 문서 안내

Engineering Lab은 실무 백엔드 문제를 재현 가능한 실험으로 만들고, 가설·조건·결과·해석을 하나의 증거 묶음으로 남겨 기술 판단을 숫자로 설명하기 위한 개인 실험실입니다. 루트 실험 플랫폼과 별도 빌드인 [Career Lab 다섯 모듈](../career-lab/README.md)로 구성됩니다.

문서는 두 종류입니다. **현재 동작** 문서는 지금 코드가 하는 일만 적고 변경 때 함께 갱신합니다. **기록** 문서(설계·계획·릴리스·결정)는 작성 시점의 판단을 남기며, 상태 줄로 완료 여부를 표시합니다.

## 시작하기

1. [프로젝트 README](../README.md): 기능, 준비 사항, 빠른 시작, 구조와 제한
2. [단계별 사용 가이드](user-guide.md): 큐·IO·Excel 실행, Run 조회·비교·다운로드
3. [시나리오와 CLI](scenarios.md): 실행 조건 파일, 반복 측정·기준선 판정·파라미터 스윕
4. [Run 증거](run-evidence.md): 비교 의미, export와 artifact 보존
5. [Finding 작성](findings/README.md): 관측과 해석을 구분해 결과 기록, 대시보드 초안 내려받기

## 현재 동작

| 필요 | 문서 |
| --- | --- |
| HTTP 요청·응답·페이지·오류 코드, 반복 묶음·스윕·캡처 정리 API | [API 참조](api-reference.md) |
| 서버 설정·정상 종료·백업·복원·문제 해결 | [운영 안내](operations.md) |
| 저장소 버전(v4)·배압 writer·dead-letter·종료 계약·이행 | [Run 저장](run-storage.md) |
| JVM·Trace·JFR·로그의 범위와 비용 | [관측](observability.md) |
| 처리량·제한 힙 실험 재현 | [Pipeline benchmark](pipeline-benchmark.md) |
| 독립 대시보드 실행·화면 | [사용 가이드](standalone-dashboard.md), [개발/검증 명령](../frontend/README.md) |
| 대시보드 시각 규칙과 화면별 데이터 규칙 | [시각 설계](dashboard-visual-design.md) |
| 독립·내장 배포, 기존 주소 전환 | [배포](dashboard-deployment.md) |
| Career worker 관측·REPORT·용량 제한·복구 | [통합 대시보드 운영](dashboard-operations.md), [Career worker 배포](career-worker-deployment.md) |
| worker별 실행·측정·복구 | [Equity](equity-worker.md), [Batch/Excel memory](batch-memory-worker.md), [MDS](mds-worker.md), [Creator](creator-worker.md), [MCP checks](mcp-checks-worker.md) |
| worker 장애 주입·복구·컨테이너 격리 | [장애 주입](worker-fault-injection.md), [worker 복구](worker-recovery.md), [sequence 컨테이너 복구](sequence-container-recovery.md), [격리](worker-container-isolation.md) |
| 전체 모듈 로컬 배포·상시 실행·백업 | [로컬 배포·인수](local-deployment.md), [macOS 상시 실행](local-service.md), [백업·복원 자동화](local-backup.md) |
| 분야 중심 메뉴와 별도 경험 사례 | [탐색 구조·캡처](catalog-navigation.md) |
| 공통 검증 성질·측정 범위·비교 가능 조건 | [측정 계약](measurement-contract.md) |
| Excel/Equity 공통 처리 계약·재사용 경계 | [파이프라인 계약](pipeline-contract.md) |
| 전략·사례·시나리오 등록 계약 | [Study catalog](study-catalog.md) |
| 새 실험·모듈 추가, 패턴 재사용 참조 사례 | [확장 가이드](adding-experiments.md), [Batched checksum](../experiments/examples/batched-checksum/README.md) |
| 코드 탐색·검증·커밋 규칙 | [Codex 안내](codex.md), [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md) |

## 기록

| 종류 | 문서 | 상태 |
| --- | --- | --- |
| 결정 | [설계 결정 기록(ADR)](adr/README.md) | 계속 추가 |
| 측정 결과 | [Pipeline Finding 2026-10-04](findings/2026-10-04-pipeline-throughput-heap.md) | 측정 완료 |
| 릴리스 | [v0.3.0](releases/v0.3.0.md), [v0.3.0 운영 전환](releases/v0.3.0-deployment.md) | 운영 중 |
| 릴리스 | [v0.2.0](releases/v0.2.0.md), [v0.2.0 운영 전환](releases/v0.2.0-deployment.md) | 이전 버전 |
| 릴리스 | [v0.1.0](releases/v0.1.0.md), [v0.1.0 운영 전환](releases/v0.1.0-deployment.md) | 이전 버전 |
| 설계·계획 | [패턴 중심 플랫폼 설계](pattern-platform-design.md), [작업 계획](pattern-platform-plan.md) | P1–P7 완료 |
| 설계·계획 | [대시보드·Career 통합 설계](dashboard-integration-design.md), [이행 계획](dashboard-integration-plan.md) | D1–D6 완료 |
| 설계·계획 | [플랫폼 설계](experiment-platform-design.md), [작업계획](implementation-plan.md) | 기반 완료, 선택 확장은 계획 문서 참조 |
| 배경 | [아키텍처](architecture.md), [추출 순서](refactor-sequence.md) | 초기 방향; 목표 모듈 구성은 현재 구조가 아님 |

## 문서 유지 원칙

- 런타임 기본값의 기준은 `src/main/resources/application.yml`, 선언적 schema의 기준은 `ExperimentDescriptors`입니다. 값을 변경하면 관련 표와 실행 예제를 함께 갱신합니다.
- 현재 동작 문서에는 지금 동작만 씁니다. 진행 경과와 과거 판단은 기록 문서에, 되돌리기 어려운 결정은 ADR에 남깁니다.
- API 변경은 사용 가이드와 API 참조를 함께 확인합니다. 화면 변경은 [캡처 재현 정보](user-guide.md#캡처-재현-정보)에 따라 실제 앱을 실행해 이미지와 설명을 교체합니다. 문서 캡처는 `DOC_SCREENSHOTS=1`일 때만 갱신됩니다.
- 캡처는 합성 데이터로 작성하며 원본 Run 증거를 대신하지 않습니다. 성능 주장은 Finding의 입력·코드·환경·반복 조건과 원본 export로 추적합니다.
- 문서만 바뀌어도 `./gradlew test`, 링크/이미지와 diff 확인 후 작업 단위로 커밋합니다.
