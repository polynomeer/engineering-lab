# 공통 대시보드 이행 계획

상태: **등록 확장 기반·D1 완료, D2 실행 화면·도구·상태 이행 완료** · 기준: [통합 설계](dashboard-integration-design.md)

복구된 Career Lab과 현재 실험 플랫폼을 유지한 채 단계적으로 연결한다. 아래 작업은 각각 검증 후 독립 커밋한다. 각 단계의 완료 표시를 구현 기준으로 삼는다. 전체 worker 연동은 아직 완료되지 않았다.

## 진행 원칙

- 모든 작업 단위에서 `./gradlew test`를 실행한다. frontend 도입 후에는 lockfile 기반 설치, typecheck, 단위 테스트, production build를 해당 변경에 추가한다.
- career 소스·빌드·harness 변경은 `./scripts/career-lab.sh test`도 필수다. 기존 42개 테스트를 교체·제외해 통과시키지 않는다.
- UI 변경은 실제 브라우저에서 검증하고 캡처·사용 가이드와 기존 route 회귀 검증을 함께 갱신한다.
- 표에 적힌 신규 task/API/파일 이름은 제안이다. 구현 후 실제 이름·검증 결과·커밋을 이 문서에 기록한다.
- 성능 수치나 단계 완료를 예측값으로 채우지 않는다. 원본 결과·실패 이유를 남긴다.

## D0. 구조·호환 설계 — 완료

- [x] 기존 화면/API, 브라우저 저장 key, registry/runner/저장소 경계를 확인한다.
- [x] 독립 UI·중앙 API·Java 21 worker 구조와 대안을 비교한다.
- [x] URL·데이터·관측·기존 JDK/Boot 빌드 호환을 정의한다.
- [x] 다섯 모듈의 13개 시나리오와 단계별 완료 조건을 작성한다.

커밋: `docs(architecture): design shared dashboard and career integration`.

## E0. 지속적인 모듈 확장 기반 — 완료 (2026-10-05)

- [x] 기존 세 실험의 descriptor와 runtime registration을 각 모듈 패키지로 옮긴다.
- [x] ServiceLoader catalog를 CLI와 서버에서 공유하고 ID/metric 충돌·binding 누락을 검증한다.
- [x] `GET /modules`로 모듈 소유 관계를 제공하고 기존 API·ID·schema를 유지한다.
- [x] Career의 고정 include 목록을 선언 기반 발견으로 바꾸고 추가 모듈/잘못된 선언 fixture를 검증한다.
- [x] [실험·Career 추가 가이드](adding-experiments.md)를 작성한다.

검증: 루트 141개 테스트 통과, 임시 Gradle 프로젝트 기반 발견/거부 테스트 4개 통과. Career 전체 테스트도 성공했으며 기존 42개 테스트 결과를 재사용했다(UP-TO-DATE). 이 단계는 D3의 executor 분리나 D4의 worker 연동을 완료한 것이 아니다.

## D1. 현재 계약 고정과 프론트엔드 골격 — 완료 (2026-10-05)

- [x] 현 API 응답 fixture와 HTTP status/header 계약을 고정한다. 기존 Run·비교·artifact·에러·cursor fixture를 우선한다.
- [x] `frontend/`에 제안 스택을 도입하고 Node/package manager 버전·lockfile을 고정한다. 공통 API client, `/app` router, `/api` 개발 proxy, 오류/로딩 상태를 만든다.
- [x] Run 목록·상세·비교 읽기 화면을 실제 기존 서버와 연결한다. 신규 capabilities 404는 legacy 모드로 처리한다.
- [x] CI/local 검증 명령을 README와 Codex 안내에 기록한다. 루트 Gradle 빌드에 frontend 개발 환경을 강제하지 않는다.

완료 기준: 기존 서버를 수정하지 않고 별도 frontend에서 저장된 Run 두 개를 비교·export하고, deep link를 새로고침해도 동작한다. API 오류는 HTML 성공으로 가려지지 않는다. 기존 모든 화면은 계속 제공된다.

계약 고정 커밋: `632e29f`. 화면 구현 커밋 제목: `feat(ui): add standalone run dashboard`.

검증: 루트 전체 테스트 142개 통과, frontend typecheck/build 및 단위 테스트 5개 통과. 실제 서버의 생성·목록·상세·비교·export·deep link·375px·키보드·legacy fallback·오류/cursor를 브라우저 테스트 3개로 확인했다. [사용 가이드와 캡처](standalone-dashboard.md), [실행 명령](../frontend/README.md)을 참고한다.

## D2. 기존 실행 화면과 상태 이행

- [x] 큐/IO 폼·프리셋·비교·CSV를 공통 컴포넌트로 이관한다. 새 CSV는 비교 요약이며 기존 시계열 CSV는 구화면에서 유지한다.
- [x] Excel 업로드·작업 상세·현재 DB 도구와 IO 단건 데모를 이관한다. 데모의 비영속 의미를 유지한다.
- [x] legacy localStorage/sessionStorage import와 다른 origin 간 설정 export/import를 구현한다.
- [ ] 기존 route→신규 route 변환, query 보존, 선택적 302와 legacy rollback 설정을 구현한다.
- [ ] standalone 정적 배포와 embedded 선택 패키징에서 같은 산출물을 검증한다. API prefix, Location rewrite, 다운로드 헤더·streaming을 확인한다.

완료 기준: 설계의 URL 대응표 전체에 브라우저 회귀 증거가 있고, 기존 북마크·폼·프리셋·이력이 보존된다. 기존 동작과 다른 부분은 명시적으로 기록한다. 접근성·375px·스토리지 비활성 상태를 확인한다.

큐/IO 화면 검증 (2026-10-05): schema 기반 폼, 기존 6개 프리셋, 최근 설정 12개 저장·복원, 비교 요약 CSV/JSON을 추가했다. 루트 `./gradlew test` 성공(기존 142개 결과 UP-TO-DATE), frontend 단위 테스트 9개·브라우저 테스트 5개와 typecheck/build 통과. 실제 큐·IO Run 성공, 재접속 후 설정 복원, CSV 다운로드, 375px, 저장소 차단, 불확실한 접수의 재시도 방지를 확인했다. 사용법·기존 CSV와의 차이는 [가이드](standalone-dashboard.md)에 기록했다. 기존 설정의 origin 간 이행은 아직 구현하지 않았다.

Excel/도구 검증 (2026-10-07): PIPELINE/SINGLE_THREAD 실제 XLSX 업로드와 Run 상세·행 집계, 현재 DB 제한 조회, 비영속 IO 두 방식·CSV를 확인했다. 단위 테스트 10개, 브라우저 테스트 7개, typecheck/build 및 루트 전체 테스트(UP-TO-DATE) 성공. 모듈별 입력/상세 adapter를 공통 router와 분리했다. IO 새 화면 이력은 현재 탭 20개이며 영속 브라우저 이력과 기존 key import는 다음 상태 이행 단계에 남긴다.

상태 이행 검증 (2026-10-07): 동일 origin 직접 읽기와 다른 origin의 구화면 JSON 내보내기/가져오기, 미리보기·선택·충돌 정책, 원본 보존, 단일 atomic 저장, IO 이력 복원을 추가했다. 신규 단건/기존 평균 비교는 별도 표시한다. 루트 테스트 142개, frontend 단위 테스트 15개, 브라우저 전체 9개와 typecheck/build가 통과했다. 캡처와 사용법은 [가이드](standalone-dashboard.md)에 있다.

독립 커밋 단위: 큐/IO 화면 → Excel 화면 → 상태 이행 → route/배포 호환. 화면 검증을 통과하기 전 Java HTML을 제거하지 않는다.

## D3. 실행 경계와 저장 schema 확장

- [ ] `DurableExperimentRunner`에서 공통 lifecycle/evidence와 in-process 관측을 분리한다. 기존 결과·수집 대상·종료 순서를 유지한다.
- [ ] executor 선택과 중앙 admission을 도입하고, 외부 실행의 JVM/환경을 서버 관측과 분리한다.
- [ ] v3 additive migration, execution/check 저장·페이지 조회, capabilities/modules/descriptor extension을 구현한다.
- [ ] 비교 API에 worker 환경·배포물·측정 범위 차이를 반영한다. 기존 응답 형식 안에서 cautions를 유지한다.

완료 기준: 기존 플랫폼 전체 테스트가 통과하고, v2 fixture 재개방에서 Run ID·원시 증거가 유지된다. 구형/신형 UI와 API의 세 가지 조합을 테스트한다. 신규 기능 미지원 상태를 명확히 표시한다.

독립 커밋 단위: executor 경계 추출 → v3 저장/이행 → extension API/비교. 각 단계별 DB 백업·rollback 절차를 남긴다.

## D4. Worker protocol과 하나의 수직 연동

공통 executor/manifest에 모듈 이름별 분기를 만들지 않는다. 신규 모듈 fixture도 generic 경로로 검증한다. 첫 대상은 `career.mcp-platform.sequence`다. 세 variant와 실제 동시성 결과가 명확하고, 처리량 추정보다 관측 정합성을 먼저 검증할 수 있다.

- [ ] worker JSON Schema와 정상/오류 fixture, Java 21 `worker-support`를 추가한다.
- [ ] manifest 기반 배포물 빌드·사전 점검·실행, bounded stdout/stderr, deadline·종료·container 정리를 구현한다.
- [ ] sequence driver를 추출하고 기존 JUnit 검증과 같은 코드를 사용한다.
- [ ] UI catalog → variant 실행 → 중앙 Run → checks/metrics → 비교 → 재시작 후 조회까지 연결한다.
- [ ] 별도 JVM 정보·코드 hash가 서버 정보와 구분되어 표시되는지 확인한다.
- [ ] 실패 주입: malformed/oversize/누락/역순, exit 0이나 RESULT 없음, exit nonzero, DB flush 실패, 강제 종료, pipe 포화, Docker 불가를 검증한다.

완료 기준: before 충돌과 after 중복 방지를 원시 증거로 확인하며, 두 Run이 하나의 Dashboard에 표시된다. 프로세스 정상 종료만으로 SUCCEEDED가 되지 않고 유실된 증거가 성공으로 보이지 않는다.

독립 커밋 단위: protocol/codec → process lifecycle → sequence harness → catalog/UI 연결. 범용 원격 worker 기능으로 확대하지 않는다.

## D5. 다섯 모듈의 나머지 시나리오

D4의 기반 위에서 모듈별로 완료하고 커밋한다. 하네스 변경 시 기존 회귀 테스트도 실행한다.

| 순서 | 범위 | 완료 기준 |
| --- | --- | --- |
| D5-1 | mcp-platform: architecture, validation | 정상/고의 위반, 단계별 검증을 CHECK_RESULTS로 표시; 모듈 3개 시나리오 완료 |
| D5-2 | equity-system: deletion, redis-lock, registration | 거래 점유 시간과 전체 시간 구분, 락 기대 결과, 등록 집계·측정값; 3개 완료 |
| D5-3 | batch-excel: batch-memory, excel-memory | 새 JVM별 variant 측정, sampled heap 표기, seed/측정 구간·heap 한도 기록; 2개 완료 |
| D5-4 | mds-distribution: settlement-guard, idempotency | TTL·중복 이벤트·실패 재시도 check, 격리 DB 정리; 2개 완료 |
| D5-5 | creator-studio: session-rotation, mapping-integrity, post-commit | token/매핑/commit 경계 check, 외부 연동 stub 유지; 3개 완료 |

각 모듈의 gate: schema validation → 실제 컨테이너 실행 → partial failure → raw/export → 브라우저 표시 → 재시작 조회. metric이 없는 시나리오에 빈 차트 대신 check 표를 제공한다. FACT/NEW-DESIGN 구분을 유지한다.

## D6. 관측·artifact 확장과 운영 마무리

- [ ] worker에서 지원 가능한 JVM/JFR/log capability를 명시한다. 지원한 기능만 UI에 노출한다.
- [ ] 관리 artifact 이관과 hash/path/size 검증·종류별 다운로드를 추가한다. 기존 JFR/log와 참조 전용 동작을 회귀 검증한다.
- [ ] 총 workspace quota, 실행 슬롯, timeout·배압·대용량 조회의 실제 상한을 측정한다.
- [ ] frontend+API+worker 빌드/시작/정지/복구 명령과 readiness 진단을 정리한다.
- [ ] 신규 화면 캡처로 사용 가이드를 갱신하고, 기존 캡처와 URL의 적용 범위를 표시한다.
- [ ] 전환 기본값을 변경하기 전에 legacy와 신규 화면 parity 표를 검토한다. 구화면 제거는 별도 커밋이며 API 제거와 묶지 않는다.

완료 기준: 기존 세 실험군과 Career Lab 13개 시나리오가 동일 화면에서 실행·조회·증거 보존되고, 구화면/API 호환과 장애 복구가 재현된다. 다음 절의 검증 표가 모두 완료되어야 전체 연동 완료로 보고한다.

## 최종 검증 표

| 검증 | 필수 증거 |
| --- | --- |
| 기존 코드 회귀 | 루트 전체 테스트 + Career Lab 전체 테스트 보고서 |
| frontend | typecheck/unit/build + 실제 browser E2E |
| URL/상태 호환 | 대응표 전체 deep link, origin 이행, 프리셋/CSV 테스트 |
| API 호환 | 구/신 조합, JSON/header/cursor/error/multipart/export 계약 |
| 데이터 이행 | 백업본 v2→v3, 재시작, rollback 복원 결과 |
| 실행 격리 | worker JDK/heap/DB 격리, 동시성 제한, 정리 확인 |
| 관측 정확성 | worker/controller 구분, 단위·구간·결측·expected anomaly |
| 장애 | 저장 실패·timeout·kill·protocol 오류·quota·Docker 불가 |
| 메모리/용량 | 서버 큐·worker pipe·브라우저 보유 point·artifact 상한 |
| 사용자 문서 | 시작·모듈 선택·실행·비교·다운로드·복구 캡처와 명령 |

다음 실행 작업은 **D2: 기존 URL 호환과 standalone/embedded 배포**이다. Career worker 연동은 D3/D4 기반 검증 뒤 시작한다.
