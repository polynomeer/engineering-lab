# 문서 안내

저장소는 루트 실험 플랫폼과 별도 빌드인 [Career Lab 다섯 모듈](../career-lab/README.md)로 구성됩니다. 아래 안내는 루트 플랫폼을 대상으로 합니다. 새 사용자는 아래 순서로 읽으면 실행부터 결과 보관까지 진행할 수 있습니다.

1. [프로젝트 README](../README.md): 기능, 준비 사항, 빠른 시작, 구조와 제한
2. [화면 캡처 사용 가이드](user-guide.md): 큐·IO·Excel 실행, Run 조회·비교·다운로드
3. [시나리오와 CLI](scenarios.md): 실행 조건을 파일로 관리하고 검증·재실행
4. [Run 증거](run-evidence.md): 비교 의미, export와 artifact 보존
5. [Finding 작성](findings/README.md): 관측과 해석을 구분해 결과 기록

## 목적별 참조

| 필요 | 문서 |
| --- | --- |
| HTTP 요청·응답·페이지·오류 코드 | [API 참조](api-reference.md) |
| 서버 설정·정상 종료·백업·복원·문제 해결 | [운영 안내](operations.md) |
| 저장소 버전·배압 writer·dead-letter·종료 계약 | [Run 저장](run-storage.md) |
| JVM·Trace·JFR·로그의 범위와 비용 | [관측](observability.md) |
| 처리량·제한 힙 실험 재현 | [Pipeline benchmark](pipeline-benchmark.md) |
| 실제 측정값과 원본 증거 | [Pipeline Finding](findings/2026-10-04-pipeline-throughput-heap.md) |
| 독립 대시보드 실행·화면 | [사용 가이드](standalone-dashboard.md), [개발/검증 명령](../frontend/README.md) |
| Equity 삭제·잠금·등록 worker 실행·측정·복구 | [Equity worker](equity-worker.md) |
| 새 실험·모듈 추가 | [확장 가이드](adding-experiments.md) |
| 독립 프론트엔드·Career Lab 연동 (설계안) | [통합 설계](dashboard-integration-design.md), [이행 계획](dashboard-integration-plan.md) |
| 기능 경계·실행 모델 설계 | [플랫폼 설계](experiment-platform-design.md) |
| 완료 항목과 선택 확장 | [작업계획](implementation-plan.md) |
| 코드 탐색·검증·커밋 규칙 | [Codex 안내](codex.md), [AGENTS.md](../AGENTS.md) |
| 초기 모듈 방향·패키지 이행 배경 | [아키텍처](architecture.md), [추출 순서](refactor-sequence.md) |

## 문서 유지 원칙

- 런타임 기본값의 기준은 `src/main/resources/application.yml`, 선언적 schema의 기준은 `ExperimentDescriptors`입니다. 값을 변경하면 관련 표와 실행 예제를 함께 갱신합니다.
- 설계의 목표·과거 검증 기록·현재 동작을 구분합니다. 초기 플랫폼 모듈 계획과 실제 복구된 Career Lab 모듈 구성을 구분합니다.
- API 변경은 사용 가이드와 API 참조를 함께 확인합니다. 화면 변경은 [캡처 재현 정보](user-guide.md#캡처-재현-정보)에 따라 실제 앱을 실행해 필요한 이미지와 설명을 교체합니다.
- 캡처는 합성 데이터로 작성하며 원본 Run 증거를 대신하지 않습니다. 성능 주장은 Finding의 입력·코드·환경·반복 조건과 원본 export로 추적합니다.
- 문서만 바뀌어도 `./gradlew test`, 링크/이미지와 diff 확인 후 작업 단위로 커밋합니다.

- [Batch / Excel memory worker](batch-memory-worker.md): D5-3 배포, sampled heap 해석, 실패 증거와 검증

- [MDS worker](mds-worker.md): 정산 가드·TTL·중복 전달·실패 재시도와 격리 실행

- [Creator worker](creator-worker.md): 세션 교체, 매핑 무결성, post-commit stub과 실패 증거

- [통합 대시보드 운영](dashboard-operations.md): D6 관측·REPORT 다운로드·용량 제한·배포·복구·전환 parity

- [전체 모듈 로컬 배포·인수 검증](local-deployment.md): 고정 배포물 준비, 기동, 13개 시나리오와 실패·재시작 검증

- [macOS 상시 실행](local-service.md): LaunchAgent 설치·자동 재기동·중지·제거와 소유권 검증

- [백업·복원 자동화](local-backup.md): 정기 백업·최근 백업 보존·hash 검증·별도 저장소 복원

- [릴리스 기록 v0.1.0](releases/v0.1.0.md): 포함 기능·검증 범위·업그레이드·운영 제한
