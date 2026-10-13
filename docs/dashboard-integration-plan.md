# 공통 대시보드 이행 계획

상태: **등록 확장 기반·D1·D2·D3 완료** · 기준: [통합 설계](dashboard-integration-design.md)

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
- [x] 기존 route→신규 route 변환, query 보존, 선택적 302와 legacy rollback 설정을 구현한다.
- [x] standalone 정적 배포와 embedded 선택 패키징에서 같은 산출물을 검증한다. API prefix, Location rewrite, 다운로드 헤더·streaming을 확인한다.

완료 기준: 설계의 URL 대응표 전체에 브라우저 회귀 증거가 있고, 기존 북마크·폼·프리셋·이력이 보존된다. 기존 동작과 다른 부분은 명시적으로 기록한다. 접근성·375px·스토리지 비활성 상태를 확인한다.

큐/IO 화면 검증 (2026-10-05): schema 기반 폼, 기존 6개 프리셋, 최근 설정 12개 저장·복원, 비교 요약 CSV/JSON을 추가했다. 루트 `./gradlew test` 성공(기존 142개 결과 UP-TO-DATE), frontend 단위 테스트 9개·브라우저 테스트 5개와 typecheck/build 통과. 실제 큐·IO Run 성공, 재접속 후 설정 복원, CSV 다운로드, 375px, 저장소 차단, 불확실한 접수의 재시도 방지를 확인했다. 사용법·기존 CSV와의 차이는 [가이드](standalone-dashboard.md)에 기록했다. 기존 설정의 origin 간 이행은 아직 구현하지 않았다.

Excel/도구 검증 (2026-10-07): PIPELINE/SINGLE_THREAD 실제 XLSX 업로드와 Run 상세·행 집계, 현재 DB 제한 조회, 비영속 IO 두 방식·CSV를 확인했다. 단위 테스트 10개, 브라우저 테스트 7개, typecheck/build 및 루트 전체 테스트(UP-TO-DATE) 성공. 모듈별 입력/상세 adapter를 공통 router와 분리했다. IO 새 화면 이력은 현재 탭 20개이며 영속 브라우저 이력과 기존 key import는 다음 상태 이행 단계에 남긴다.

상태 이행 검증 (2026-10-07): 동일 origin 직접 읽기와 다른 origin의 구화면 JSON 내보내기/가져오기, 미리보기·선택·충돌 정책, 원본 보존, 단일 atomic 저장, IO 이력 복원을 추가했다. 신규 단건/기존 평균 비교는 별도 표시한다. 루트 테스트 142개, frontend 단위 테스트 15개, 브라우저 전체 9개와 typecheck/build가 통과했다. 캡처와 사용법은 [가이드](standalone-dashboard.md)에 있다.

URL/배포 검증 (2026-10-07): 기본 off의 302/query 보존과 legacy 우회, `/api` 내부 adapter, Node 독립 정적/streaming proxy, 선택적 bootDashboardJar를 구현했다. 동일 dist를 양쪽에 배포해 기존 브라우저 9개와 배포 경계 1개를 각각 확인했다. 실제 Excel multipart·202 Location·NDJSON/Content-Disposition·deep link·설정 이전이 유지되며 JS byte 일치도 확인했다. 독립 서버 계약 테스트 1개, frontend 단위 15개, 루트 전체 144개가 통과했다. 내장 jar의 모든 dist 파일 byte 일치 및 기본 jar의 SPA 미포함도 확인했다. 기본 bootJar에는 SPA를 포함하지 않는다. [시작·되돌리기 절차](dashboard-deployment.md).

독립 커밋 단위: 큐/IO 화면 → Excel 화면 → 상태 이행 → route/배포 호환. 화면 검증을 통과하기 전 Java HTML을 제거하지 않는다.

## D3. 실행 경계와 저장 schema 확장 — 완료 (2026-10-08)

- [x] `DurableExperimentRunner`에서 공통 lifecycle/evidence와 in-process 관측을 분리한다. 기존 결과·수집 대상·종료 순서를 유지한다.
- [x] 서버 등록 기반 executor 선택과 전체/실행기별·배타 admission을 도입한다.
- [x] v3 execution에 worker 환경을 별도로 저장하는 계약을 구현한다. 실제 handshake 연결은 D4에 남긴다.
- [x] v3 additive migration, execution/check 저장·페이지 조회와 백업/복원을 구현한다.
- [x] 앱의 runner 자동 기록과 HTTP execution/check, 별도 확장 export를 구현한다.
- [x] capabilities/modules/descriptor extension과 화면 호환 조합 검증을 마무리한다.
- [x] 비교 API에 worker 환경·배포물·측정 범위 차이를 반영한다. 기존 응답 형식 안에서 cautions를 유지한다.

완료 기준: 기존 플랫폼 전체 테스트가 통과하고, v2 fixture 재개방에서 Run ID·원시 증거가 유지된다. 구형/신형 UI와 API의 세 가지 조합을 테스트한다. 신규 기능 미지원 상태를 명확히 표시한다.

첫 단위 구현: `ExperimentExecutor`의 streaming sink 계약과 `InProcessExecutor`를 추출했다. 중앙 runner가 Run 생성/시작/완료, bounded writer, preview, dead-letter/artifact 저장을 소유한다. 기존 생성자와 관측 설정·결과는 유지한다. 환경 수집 위치는 다음 선택/admission 단위에서 아래와 같이 정리했다. 종료 순서와 저장 실패 로그, 중앙 lifecycle/환경 전달을 경계 테스트로 고정했다. DB는 v2 그대로이며 데이터 이행이 없어 이전 바이너리로 rollback할 수 있다. 실행 중 교체하지 않고 기존 종료 절차를 따른다. 이 첫 단위에서는 외부 worker 환경 handshake와 executor routing은 구현하지 않았다.

경계 추출 검증 (2026-10-08): `./gradlew test` 전체 146개 통과(실패·건너뜀 0). 기존 JVM/trace/JFR/log·dead-letter·재시작/API 회귀와 신규 경계 테스트 2개를 포함한다.

선택/admission 구현 (2026-10-08): 불변 `ExperimentExecutors`가 서버 등록 정보로 실행기를 선택하고 `ExecutionAdmission`이 Run ID별로 전체·실행기별 상한과 배타 실행을 적용한다. 기본 앱은 IN_PROCESS와 기존 전체 상한 8을 유지한다. 별도 대기 큐나 worker 설정은 아직 도입하지 않았다. 중복 Run 실행은 소유 Run을 실패로 바꾸지 않으며 종료·실패 시 슬롯을 반환한다. 설계의 metadata 호환 원칙에 따라 기존 environment는 중앙 runner가 수집하는 서버 환경으로 고정했다. 다른 실행기에 서버 관측이 붙지 않는 것은 test executor로 검증하며, 실제 worker 환경은 v3/HELLO 구현 전까지 지원하지 않는다. DB는 여전히 v2로 rollback 조건은 첫 단위와 같다.

선택/admission 검증 (2026-10-08): `./gradlew test` 전체 152개 통과(실패·건너뜀 0). 신규 6개 테스트로 등록 오류, 입력에 의한 routing 변경 방지, 관측 분리, 전체/개별 상한, 배타 충돌, 중복 Run 보호, 종료 시 interrupt와 슬롯 반환을 확인했다.

v3 저장 구현 (2026-10-08): 기존 테이블을 유지하고 execution/check를 추가했다. worker identity는 handshake 전 null이고 등록 후 불변이며 기존 서버 metadata를 덮어쓰지 않는다. check 중복/충돌·크기/페이지 상한, phase 순서, 증거 미완료 성공 거부와 재시작 복구를 구현했다. v1/v2에서 DDL 전 DB ZIP 백업을 생성하고 실패 시 중단하며, 부분 DDL 재개와 별도 디렉터리로 v2 백업 복원을 검증한다. 기존 Run에는 실행 증거를 추정해 채우지 않는다. 이 저장 단위 이후 자동 기록·조회·확장 export 연결을 진행했다. [운영 이행·복원 절차](run-storage.md)를 따른다.

v3 저장 검증 (2026-10-08): `./gradlew test` 전체 159개 통과(실패·건너뜀 0). 신규 7개 테스트로 병렬 check 저장·충돌·상한·phase/worker/완료 조건·재시작, 실제 v2 fixture의 원시 증거 보존·ZIP 복원, 백업 실패와 부분 DDL 재개를 확인했다. v1 이행 회귀도 유지한다.

자동 기록/API 구현 (2026-10-08): 앱 catalog의 module ID로 Run/definition을 atomic 생성하고 in-process phase와 성공 후 evidenceComplete를 기록한다. 저장·수집 실패는 완료로 표시하지 않는다. `/dashboard/v1/runs/{id}/execution`, `/checks`, `/export`를 추가했으며 별도 export는 schemaVersion=2로 execution/check를 포함한다. 기존 `/runs` payload와 schemaVersion=1 export는 유지한다. 기존 비교 응답 안에 실제 worker 환경·Git/hash·측정 범위·미확인 정보 cautions를 추가했다. 과거 Run과 직접 runner 호환 생성자는 execution이 없을 수 있다. capability/catalog extension, 신규 화면과 실제 worker는 아직 구현하지 않았다.

자동 기록/API 검증 (2026-10-08): `./gradlew test` 전체 165개 통과(실패·건너뜀 0). 신규 6개 테스트와 실제 앱 통합 검증으로 atomic 생성 rollback, phase/seal/writer 실패, 미기록·부분 worker 증거, 페이지 경계, 재개방, 기존/신규 export와 비교 형식 유지를 확인했다. 화면 코드는 변경하지 않았으며 전용 UI와 capability 호환 조합 검증은 다음 단위다.

capability/catalog 구현 (2026-10-08): `/dashboard/v1/capabilities`, `/modules`, `/experiments/{id}`를 추가했다. 등록 catalog에서 소유 모듈·실험을 유도하고 현재 연결된 IN_PROCESS만 READY로 보고한다. LOCAL_JVM·취소는 false, worker protocol 목록은 비어 있으며 관측 기능은 실제 설정을 반영한다. 기존 descriptor/API는 유지한다. 신규 화면은 schemaVersion=1만 인식하며 404·알 수 없는 버전은 기존 API 호환 모드, 503은 오류로 표시한다. 실행/check 전용 화면과 Career worker는 D4 이후 범위다.

호환 검증 (2026-10-08): 루트 전체 167개, 프론트엔드 단위 17개와 production build가 통과했다. 신규 API 브라우저 12항목은 11개 통과 후 행 머리글 선택자를 수정한 1개 재검증까지 통과했다. 실제 D2 서버(`94c1913`, 별도 임시 DB)에서도 신규 화면의 실행·조회·비교·내보내기와 호환 처리 4개가 통과했다. 기존 UI→신규 API, 신규 UI→신규 API, 신규 UI→기존 API 세 조합을 확인했다. [기존 비교 화면 캡처](images/15-extension-legacy-comparison.png)는 동일 Run의 확장 조건 표시를 확인한 것으로 성능 비교 근거가 아니다.

독립 커밋 단위: executor 경계 추출 → 선택/admission → v3 저장/이행 → extension API/비교. 각 단계별 DB 백업·rollback 절차를 남긴다.

## D4. Worker protocol과 하나의 수직 연동

공통 executor/manifest에 모듈 이름별 분기를 만들지 않는다. 신규 모듈 fixture도 generic 경로로 검증한다. 첫 대상은 `career.mcp-platform.sequence`다. 세 variant와 실제 동시성 결과가 명확하고, 처리량 추정보다 관측 정합성을 먼저 검증할 수 있다.

- [x] worker JSON Schema와 정상/오류 fixture, Java 21 `worker-support`를 추가한다.
- [x] 서버 소유 manifest 모델과 jar/hash 사전 점검, bounded stdout/stderr·deadline·직접 자식 종료·저장 callback 경계를 구현한다.
- [x] 중앙 RunStore worker/check/metric 어댑터를 실제 Java 21 fixture·H2로 검증한다.
- [x] REPORT 파일의 경로·크기·해시 검증과 제한된 사본 보관·artifact 참조 등록을 연결한다.
- [x] 실제 sequence worker 배포 jar·manifest와 격리 MySQL 정상 정리를 구현한다.
- [x] 실제 sequence worker의 부모 EOF/deadline/강제 종료와 정상 Docker 환경의 세션별 컨테이너 정리를 검증한다.
- [x] 서버 소유 배포물의 bounded probe로 Java/protocol/Docker 준비 상태를 확인하고 접수 전 거절한다.
- [x] 등록 worker의 영속 실행 기록으로 비정상 종료 후 새 접수를 차단하고 수동 복구 절차를 제공한다.
- [x] 살아 있는 controller가 관찰한 자손을 제한된 개수로 추적하고 정상 종료·timeout·인터럽트 시 정리한다.
- [x] sequence 컨테이너 생성 전 소유권 기록·배타 잠금과 실행별 복구 명령을 제공한다.
- [x] 종료 확인 기록이 있는 sequence의 opt-in 시작 시 재조정·접수 재개를 연결한다.
- [x] 실제 복구 배포물의 Docker HTTP 장애·응답 유실·무응답과 새 프로세스 재시도 시 접수 차단을 검증한다.
- [x] 시작 시 복구 명령에도 관찰된 자손 정리를 적용하고 정상 응답·시간 초과·인터럽트 회귀를 검증한다.
- [x] 종료 확인 기록이 있는 실행의 전용 Docker 엔진 SIGKILL·재시작·소유 자원 회수와 테스트 자원 삭제를 검증한다.
- [x] 비특권 worker 컨테이너 경계에서 분리 프로세스·controller 강제 종료·새 회수 프로세스를 검증하고 설계를 확정한다(I1).
- [x] 영속 소유권과 컨테이너 생성·시작·회수 어댑터를 구현하고 미확인 요청 차단을 검증한다(I2).
- [ ] container transport·sequence DB 소유자 분리·종료 미확인 복구를 연결·검증한다(I3–I5).
- [x] sequence driver를 추출하고 기존 JUnit 검증과 같은 코드를 사용한다.
- [x] 선택적 서버 catalog → variant API 실행 → 중앙 Run/checks/metrics/보고서·비교 단위를 연결한다.
- [x] 전용 UI catalog/실행/check 화면과 정상 서버 재시작 후 UI 조회를 연결한다.
- [x] 별도 JVM 정보·코드 hash가 서버 정보와 구분되어 표시되는지 확인한다.
- [ ] 실패 주입: malformed/oversize/누락/역순, exit 0이나 RESULT 없음, exit nonzero, DB flush 실패, 강제 종료, pipe 포화, Docker 불가를 검증한다.

완료 기준: before 충돌과 after 중복 방지를 원시 증거로 확인하며, 두 Run이 하나의 Dashboard에 표시된다. 프로세스 정상 종료만으로 SUCCEEDED가 되지 않고 유실된 증거가 성공으로 보이지 않는다.

protocol/codec 구현 (2026-10-08): [worker v1 계약](../contracts/worker/v1/README.md)에 command/event JSON Schema와 합성 정상·실패·오류 fixture를 추가했다. Java 21 `worker-support`는 infrastructure 선언으로 자동 발견하며 Boot/루트 core에 의존하지 않는다. 한 레코드 64 KiB·총 64 MiB·100,000개 상한, 엄격한 UTF-8/JSON, HELLO identity, 연속 sequence, phase 순서, RESULT 집계/마지막 순번과 EOF를 검증한다. 읽기/쓰기 실패 후 세션을 재사용하지 않으며, exit 0만으로 성공을 확정하지 않는다. 실제 worker 실행·부모 감시·stderr/컨테이너 정리·DB flush는 다음 단위다. 따라서 runtime capability는 변경하지 않았다.

protocol/codec 검증 (2026-10-08): 신규 32개 테스트 통과(통신 30, 독립 schema 검증 2), Java 21 jar 생성 성공. `./scripts/career-lab.sh test`가 성공했고 기존 다섯 모듈의 42개 결과는 UP-TO-DATE로 재사용했다. Docker 28.0.4 동작을 확인했다. 필수 `./gradlew test`도 성공했으며 기존 167개 결과를 재사용했다. 프로세스/DB 통합 테스트 결과로 해석하지 않는다.

process transport 구현 (2026-10-09): Java 21 `WorkerManifest`/`WorkerProcess`와 worker 측 `ParentConnection`을 추가했다. 서버 소유 Java/jar/실험 허용 목록, 복사 전후 SHA-256, 작업 폴더 분리, 크기 제한 stdout 및 stderr drain, monotonic deadline, 직접 자식 종료와 동기 저장/flush callback을 구현했다. 실제 JVM 오류 주입은 테스트용 실행 jar로 수행한다. 중앙 RunStore·실제 배포물·Docker/컨테이너·자손 프로세스 정리·실패 stderr 보존·workspace quota는 아직 미연결이며 capability는 계속 꺼 둔다. [상세 책임과 제한](../career-lab/worker-support/README.md#별도-jvm-실행-기반-d4-두-번째-단위)을 따른다.

process transport 검증 (2026-10-09): 신규 프로세스 테스트 14개와 기존 protocol/schema 32개, 총 46개 통과. 실제 Java 21 자식의 stderr 2 MiB, RESULT 누락·오류·초과 출력, nonzero exit, deadline, 종료에 응답하지 않는 자식의 강제 종료, 부모 EOF, 저장 지연/flush 실패와 종료 확인을 검증했다. Career 전체 테스트는 성공했고 기존 다섯 모듈 42개는 UP-TO-DATE로 재사용했다. `./gradlew test`도 성공했으며 루트 167개는 기존 결과를 재사용했다.

중앙 저장 어댑터 구현 (2026-10-09): 루트에서 Java 21 worker-support만 composite 의존성으로 사용하며 기존 Career Boot 앱은 독립 빌드를 유지한다. `LocalJvmExecutor`가 HELLO 환경/Git/hash·phase·허용 metric·별도 check cursor를 RunStore sink와 bounded metric writer로 전달한다. RESULT 후 EOF/exit/drain/프로세스 정리까지 확인한 경우에만 중앙 runner가 증거를 완료 처리한다. 저장 오류·worker 실패는 부분 증거를 유지하며, 파일 검증 전 ARTIFACT는 실패시킨다. 실제 Career catalog 등록·배포·Docker 정리는 미완료다. root 빌드/테스트에는 JDK 25와 21이 필요하고 두 wrapper를 동시에 실행하지 않는다.

중앙 저장 어댑터 검증 (2026-10-09): 루트 전체 174개 테스트 통과(신규 7개, 실패·건너뜀 0). 실제 worker Java 21/서버 Java 25 환경 분리, DB 재개방, worker 실패의 부분 증거, identity/check/metric flush 실패, 미등록 metric·미검증 artifact 거부를 확인했다. Career 전체 명령도 성공했다. worker-support 46개는 재실행해 통과했고 기존 다섯 모듈 42개는 성공 결과를 재사용했다. Docker 28.0.4 동작도 확인했다.

worker 보고서 구현 (2026-10-09): `WorkerArtifactImporter`를 선택적으로 LocalJvmExecutor에 연결했다. REPORT만 허용하고 경로 탈출·링크·비정상 파일·크기/hash 불일치를 거부한다. 8 KiB 버퍼와 실행별 32개/파일당 16 MiB/합계 64 MiB 상한을 적용하며 원자 이동 후 DB 참조를 등록한다. 등록 실패 시 새 사본을 제거하고 앞서 보존한 증거는 유지한다. 실제 Java 21 보고서의 보관·H2 재개방과 해시 실패를 검증한다. [한도·파일시스템 요건·복구 한계](run-evidence.md#worker-보고서-검증보관)를 따른다. JFR/LOG·보고서 다운로드 UI·운영 quota/잔여 파일 정리와 실제 Career 등록은 후속 범위다.

worker 보고서 검증 (2026-10-09): 루트 전체 182개 테스트 통과(신규 8개, 실패·건너뜀 0). 실제 Java 21 파일 생성→검증된 중앙 사본→H2 재개방을 확인하고 hash 오류가 완료 증거로 봉인되지 않음을 검증했다. Career 전체 명령도 성공했다. worker-support 46개는 재실행했고 기존 다섯 모듈 42개는 성공 결과를 재사용했다. 기존 관리 다운로드의 경로 정책은 변경하지 않았다.

sequence 배포 구현 (2026-10-09): `mcp-platform`의 기존 세 allocator와 공용 `ConcurrentAllocationHarness`를 실제 Java 21 worker에서 재사용한다. `labRuntime` 전용 Boot jar와 SHA-256 배포 descriptor를 만들고, 실행마다 독립 MySQL 8.0.39를 생성한다. worker는 실제 환경·metric·기대 이상 현상 CHECK·JSON 보고서를 전송하고 Spring/MySQL 정리 후 RESULT를 보낸다. 라이브러리를 풀어 읽도록 설정해 Boot nested jar와 Testcontainers MySQL 설정 추출의 호환 문제도 해결했다. 정상 정리를 배포물로 검증하며, 중앙 catalog/Docker readiness·강제 종료 복구·UI 연결은 아직 남아 있다. [실행 계약과 측정 제한](../career-lab/mcp-platform/README.md#별도-jvm-sequence-worker-d4)을 따른다.

sequence 배포 검증 (2026-10-09): `./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest` 성공. mcp-platform 19개(기존 16개 + 실제 배포 JVM 3개)를 재실행해 통과했고 나머지 72개는 성공 결과를 재사용했다. 세 variant의 CHECK·보고서와 실행 후 MySQL 제거를 확인했다. 배포 descriptor의 SHA-256 일치를 별도로 확인했고, `./gradlew test`도 성공했다(루트 182개 성공 결과 UP-TO-DATE). 실패·건너뜀은 없다.

sequence 장애 처리 구현 (2026-10-09): 최초 중단 사유를 interrupt flag와 독립적으로 보존하고, 준비·측정 경계와 정리 후 완료 지점에서 확인한다. 부모 EOF/deadline이 겹쳐도 강제 종료 감시 스레드는 하나만 생성한다. 정리 완료 시 INTERRUPTED, 정리 실패 시 FAILED, 정리 제한 초과 시 exit 20/미완료 protocol로 처리하며 SUCCEEDED로 바꾸지 않는다. Ryuk 비활성 설정은 DB 생성 전 거절한다. 실제 배포 jar의 연결 종료·deadline·강제 종료·보고서 쓰기 실패와 다른 Testcontainers 세션 보존을 검증한다. Docker daemon/Ryuk 자체 장애와 재시작 조정·일반 자손 정리는 범위 밖이며 중앙 capability는 계속 비활성이다.

sequence 장애 검증 (2026-10-09): 신규 8개 테스트(실제 배포 jar 장애 5개, 중단 상태 3개)를 추가했다. `./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest` 성공. mcp-platform 27개를 재실행해 모두 통과했고 나머지 72개는 성공 결과를 재사용했다. 부모 EOF/deadline, 강제 종료 후 MySQL 제거·다른 세션 보존, 보고서 생성 실패·Ryuk 비활성 거절을 확인했다. `./gradlew test`도 성공했다(루트 182개 성공 결과 UP-TO-DATE). 실패·건너뜀은 없다.

sequence 중앙 등록 구현 (2026-10-09): 기본 비활성을 유지하며 `lab.career.sequence.enabled` 설정으로 모듈·schema·실행기를 함께 등록한다. 서버 소유 descriptor/hash/Java 경로를 검증하고, 동일 jar 사본의 bounded `--probe`로 Java 21/protocol/Docker 준비 상태를 확인한다. 상태는 5초 캐시하며 준비되지 않은 접수는 Run 생성 전 503이다. 모듈 이름 분기 없이 `LocalJvmRegistration`이 실행기·배타 슬롯·worker evidence 정의를 연결한다. 세 variant는 공통 `/runs`로 접수하고 중앙 CHECK/metric/보고서 참조·비교 단위를 사용한다. worker의 미지원 관측은 capability false다. 기존 SPI catalog에 선택적 모듈 bean을 합치고 HTTP metric schema/비교에 모듈 단위를 반영한다. [배포·검증 가이드](career-worker-deployment.md)를 따른다. 전용 UI와 오프라인 CLI Career 검증, daemon/재시작 조정·일반 자손·작업 폴더 quota는 후속 범위다.

sequence 중앙 등록 검증 (2026-10-09): `./gradlew test careerSequenceTest` 성공. 루트 191개는 이번 작업 중 재실행해 통과했고 최종 명령에서는 성공 결과를 재사용했다. 별도 실제 API 통합 테스트 1개에서 세 variant의 Java 21 worker 실행, Java 25 서버 환경 분리, CHECK·보고서·비교 단위와 저장소 재개방 후 조회를 확인했다. `./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest`도 성공했다. Career 99개 중 mcp-platform 27개와 worker-support 46개를 재실행했고 나머지 26개는 성공 결과를 재사용했다. 모두 실패·건너뜀 0이다. 검증 중 Docker 환경 장애를 복구했으며, 느린 MySQL 준비를 위한 테스트 실행 한도만 5분으로 조정했다. 실제 측정 30초와 중단 후 정리 제한은 유지한다. 브라우저 전용 화면과 전체 서버 재시작 검증은 이 결과에 포함하지 않는다.

sequence 화면 구현 (2026-10-10): 기존 schema 기반 실행 폼에 준비 상태 gate와 세 variant 프리셋을 연결했다. 확인 중/불가 상태는 입력·실행을 잠그고 재확인 시 작성한 설정을 유지한다. 모듈 안내는 `moduleViews`에서 선언한다. 공통 Run 상세는 capability가 명시적으로 지원한 실행 정보·검증 결과·확장 NDJSON을 표시하며, worker 환경/Git/배포 hash를 서버 정보와 구분한다. 검증 결과는 종료 후 서버 페이지 크기 이하로 한 페이지만 보유하고 별도 cursor로 이동한다. 부분 증거·미기록·빈 결과·실패를 통과로 숨기지 않는다. [캡처 포함 사용법](standalone-dashboard.md#career-sequence-실행과-검증-결과)을 갱신했다.

sequence 화면 검증 (2026-10-10): Node 24.19.0/npm 11.6.0에서 typecheck/build, 프론트엔드 단위 17개, 독립 서버 테스트 1개 통과. 기본 설정의 브라우저 회귀 14개와 Career 설정의 브라우저 테스트 3개(실제 BEFORE/ATOMIC_UPDATE 실행 1개 포함), 동일 저장소로 정상 서버 재시작 후 UI 재조회 테스트 1개를 통과했다. 실패/부분 증거·미기록 worker·check cursor·실행 중 check 조회 보류·구형 capability fallback을 합성 응답으로 확인했다. 2.5초 지연된 실행 증거 응답이 Run 갱신에 반복 취소되지 않는 회귀 검증도 통과했다. 실제 실행에서는 worker Java 21, 두 CHECK, 확장 내보내기, 비교 단위와 375px 폭을 확인했다. `./scripts/career-lab.sh test` 성공(Career 99개 중 mcp-platform 27개 재실행, 나머지 72개 성공 결과 재사용). 배포 manifest도 재생성했다. 마지막 `./gradlew test`는 루트 191개 성공 결과를 재사용해 통과했다. 정상 재시작 후 저장 증거 보존과 Docker/호스트 비정상 종료 복구는 구분하며, 후자는 미완료다.

독립 커밋 단위: protocol/codec → process lifecycle → sequence harness → catalog/UI 연결. 범용 원격 worker 기능으로 확대하지 않는다.

복구 접수 보호 구현 (2026-10-10): 등록 LOCAL_JVM은 시작 전 4 KiB 이하의 배타 영속 기록을 만들고 성공·정리 확인 후에만 제거한다. 실패·강제 종료 후 동일 저장소로 재시작하면 readiness와 새 접수를 차단한다. 별도 프로세스 강제 종료, 기록 소유권·불완전/링크 파일 거부, Java 21 worker 성공 후 연속 실행 및 실패 후 증거 보존·재등록 거절을 검증했다. `./gradlew test` 전체 195개 통과(실패·오류·건너뜀 0). 별도 `./gradlew careerSequenceTest`도 통과하여 실제 Docker/MySQL의 세 variant 연속 성공·증거 저장·저장소 재개방을 확인했다. [운영자 복구 절차](worker-recovery.md)를 제공하며 자동 자원 회수와 전원 장애 내구성은 이 단위의 보장 범위가 아니다.

관찰된 자손 종료 구현 (2026-10-10): `WorkerProcessTree`가 50ms 간격으로 실행 전체 최대 128개 자손 identity를 추적한다. 정상 결과·timeout·호출 인터럽트 모두에서 관찰된 자손과 worker를 종료 유예 후 강제 종료하며 시작 시각을 대조한다. 추적 상한·identity/권한·종료 불확실성은 실패로 반환해 영속 복구 기록을 유지한다. 실제 Java 21 자손의 종료·무관한 프로세스 보존과 상한·PID 재사용·identity 누락을 검증한다. [관찰 한계](worker-recovery.md#실행-중-관찰한-자손-프로세스-종료)에 해당하는 프로세스 격리와 컨테이너 자동 재조정은 후속 범위다.

관찰된 자손 종료 검증 (2026-10-10): 최종 `./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest` 성공. Career 105개 중 worker-support 52개와 mcp-platform 27개를 재실행했고 나머지 26개는 성공 결과를 재사용했다. 신규 6개 테스트는 실제 자손의 정상/timeout/인터럽트 정리·무관한 프로세스 보존과 합성 상한/identity 누락/PID 재사용을 다룬다. 실제 MySQL 세 variant와 기존 부모 EOF/deadline/보고서 실패/강제 종료·세션 격리 회귀도 통과했다. 이어서 `./gradlew test` 루트 전체 195개를 재실행해 통과했다. 모두 실패·오류·건너뜀 0이다.

sequence 소유권·복구 명령 구현 (2026-10-13): worker는 workspace 배타 잠금을 유지하고 MySQL 생성 전에 Run·배포 hash·Docker 엔진·UUID 토큰을 최대 4 KiB 기록으로 저장한다. 별도 배포 프로세스의 `--recover <workspace> <runId>`는 같은 잠금과 소유권을 검증해 해당 컨테이너만 회수하며 60초 종료 제한을 적용한다. 실행 중 잠금·손상/링크 기록·다른 엔진·모호한 후보·라벨/이름/이미지 불일치를 거부하고 기록과 중앙 접수 차단을 보존한다. [복구 계약과 운영 절차](sequence-container-recovery.md)를 따른다. 서버 시작 시 자동 재조정과 실제 daemon 장애 주입 검증은 이 단위에 포함하지 않는다.

sequence 소유권·복구 검증 (2026-10-13): `./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest` 성공. Career 120개 중 변경된 mcp-platform 42개를 재실행했고 나머지 78개는 성공 결과를 재사용했다. 신규 15개 검증으로 잠금·기록 재개방·불완전/링크/크기·Run/hash/엔진·소유권·모호한 후보·삭제 미확인·반복 복구를 확인했다. 실제 배포 명령으로 살아 있는 소유 컨테이너만 회수하고 같은 Run 라벨의 다른 컨테이너를 보존했으며, 기존 강제 종료 테스트에서 실제 worker 기록을 새 프로세스로 재개방했다. 기존 세 variant와 장애 회귀도 통과했다. 필수 `./gradlew test`는 루트 195개 성공 결과를 재사용해 통과했다. 모두 실패·오류·건너뜀 0이다. 실제 daemon 재시작/호스트 장애 실험은 미실시다.

종료 확인 기반 시작 시 복구 구현 (2026-10-13): controller가 EOF·worker 종료·증거 flush·관찰된 자손/IO 정리를 확인한 뒤 marker 소유권과 연결된 최대 4 KiB 완료 기록을 저장한다. `lab.career.sequence.recover-on-startup` 기본값 false를 유지하며, 활성화하면 RunStore 배타 소유권과 중단 Run 복원 이후 한 번 재조정한다. sequence의 정상 완료 또는 기대값 불일치만 자동 복구 후보로 허용하며, 같은 배포물의 bounded CLI가 소유권과 정리를 확인한 뒤 해당 marker만 해제한다. 원래 Run 결과·증거는 유지한다. 종료 확인 기록이 없는 강제 종료와 일반 준비/정리/통신 실패는 수동 확인을 유지한다. 공통 runtime은 복구 정책을 받으며 sequence 결과 사유 판단은 모듈 등록에 둔다. [구체적 조건과 제한](sequence-container-recovery.md#종료-확인-기록이-있는-실행의-시작-시-복구)을 따른다.

종료 확인 기반 시작 시 복구 검증 (2026-10-13): `./gradlew test careerSequenceTest` 성공. 루트 전체 222개와 실제 Docker 통합 2개를 재실행해 통과했다(실패·오류·건너뜀 0). 종료 확인 파일 유무에 따른 별도 프로세스 강제 종료·재개방, 손상/링크/소유권/배포 불일치, 불확실한 실패 차단, 복구 stdout 상한·잘못된 응답·비정상 종료·timeout 정리를 검증했다. 실제 Java 21 배포물을 사용해 종료 확인 직후의 영속 경계를 재현하고, 옵션 비활성 차단·동일 저장소 중복 기동 거절·옵션 활성 서버 재시작 복구·과거 Run 불변·후속 실행 성공·완료 기록 없는 실행 차단을 확인했다. 기존 세 variant API 회귀도 통과했다. 실제 Docker 엔진/호스트 장애 주입 검증은 아니며 Career 모듈 코드·빌드는 변경하지 않았다.

Docker 통신 장애 검증 (2026-10-13): `SequenceRecoveryTransportTest`가 실제 Java 21 배포물과 별도 중앙 복구 프로세스를 루프백 Docker API에 연결한다. 조회 503·무응답·삭제 503·삭제 후 응답 유실·삭제 후 엔진 ID 변경을 주입했다. 모든 첫 복구가 접수 차단과 완료 기록을 보존하며, 정상 응답 복원 후 새 프로세스가 같은 저장소를 열어 재조정하고 원래 Run 실패 상태·사유와 journal을 유지하는 것을 검증했다. 이미 삭제된 자원은 다음 프로세스에서 재삭제하지 않는다. 소유권 토큰·조회 상한·정확한 ID 삭제와 Testcontainers 세션 한정 정리 요청을 검사한다. `./gradlew test careerSequenceTest` 성공: 신규 장애 5개와 기존 실제 Docker/API/서버 재시작 2개를 실행해 통과했고, 루트 222개는 기존 성공 결과를 재사용했다. 모두 실패·오류·건너뜀 0이다. 실제 공유 엔진을 중단하지 않았으며 엔진/호스트 재시작·OS 격리 검증은 여전히 후속 범위다. [재현 방법](sequence-container-recovery.md#docker-통신-장애-회귀-검증)을 따른다.

독립 엔진 장애 검증 (2026-10-13): 명시적 실행 승인 후 `./gradlew test careerDaemonRecoveryTest -PlabAllowPrivilegedDaemonTest=true` 성공. 전용 privileged Docker-in-Docker의 SIGKILL·동일 데이터 재시작을 실제로 수행한 테스트 1개가 통과했고 루트 222개는 기존 성공 결과를 재사용했다(실패·오류·건너뜀 0). 엔진 중단 중 접수 차단·완료 기록 유지, 재시작 후 동일 엔진 ID·컨테이너 metadata 유지, 실제 배포 복구 명령의 소유 자원 회수·다른 토큰 자원 보존·반복 복구와 원래 실패 Run·journal 불변을 확인했다. 이미지 전달 경로와 준비 확인을 수정하고, 오래된 PID 파일로 인한 재시작 실패를 막도록 `/run`에 16 MiB tmpfs를 적용했다. 테스트의 finally에서 UUID·전체 ID를 대조해 전용 컨테이너와 익명 볼륨을 삭제하고 부재를 검증했으며, 최종 별도 조회에서도 테스트 컨테이너가 없었다. 완료 기록은 합성한 경계이며 실제 MySQL 데이터 복구·종료 미확인 worker·호스트 전원 장애·OS 격리의 증거는 아니다. [실행 범위와 잔존 자원 확인](sequence-container-recovery.md#독립-docker-엔진-재시작-검증)을 따른다.


복구 명령의 자손 정리 보강 (2026-10-13): `WorkerStartupRecovery`도 공용 `WorkerProcessTree`를 사용해 정상 응답·실패·timeout·인터럽트 이후 직접 프로세스와 관찰된 자손을 정리한다. 추적 상한 128개·관찰 간격 50ms·시작 시각 대조를 유지하며 관찰 종료/정상 종료/강제 종료에 각각 2초, IO 종료에 추가 2초를 적용한다. 정리 미확인은 복구 실패로 처리해 marker를 유지하고 호출 인터럽트는 복원한다. 실제 JVM 회귀 3개에서 정상 응답·timeout·인터럽트 시 종료 신호에 버티는 자손의 강제 종료와 무관한 프로세스 보존을 확인했다. `./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest` 성공: Career 120개 중 mcp-platform 42개·worker-support 52개를 재실행하고 나머지 26개 성공 결과를 재사용했다. 이어 `./gradlew test careerSequenceTest`로 루트 225개와 실제 배포물 통합 7개를 재실행해 통과했다(모두 실패·오류·건너뜀 0). 완료 기록 없는 실행의 자동 복구와 OS 수준 격리는 여전히 후속 범위다.

컨테이너 격리 기반 검증 (2026-10-13): [실행 경계 설계와 단계](worker-container-isolation.md)를 추가했다. controller가 Docker 자원을 소유하고 worker에는 Docker 접근을 주지 않는 별도 실행 경로를 선택했다. 기존 LOCAL_JVM/marker는 유지한다. `workerContainerIsolationTest`의 실제 Docker 검증 2개가 통과했다. PPID 1·독립 세션의 분리 프로세스가 controller kill 뒤에도 실행 중임을 확인하고, 새 JVM이 실행 중인 경계 또는 PID 공간 init을 SIGKILL한 경계를 소유권 검사 후 회수했다. 비교 컨테이너 보존·기존 미완료 marker 불변·시험 자원 삭제를 확인했다. 필수 `./gradlew test`는 루트 225개 성공 결과를 재사용했다. 실제 Java 21 transport·DB 소유자 분리·새 영속 기록/자동 해제는 아직 구현하지 않았고, 다음 단위는 I2 container lifecycle 어댑터다.

컨테이너 수명주기 구현·검증 (2026-10-13): `WorkerContainerLifecycle`이 실행별 파일 잠금과 최대 6개/각 4 KiB 영속 기록으로 생성 의도·전체 ID·시작 의도/응답·회수 의도/완료를 관리한다. `DockerContainerEngine`은 고정 비특권 프로파일, 이미지 ID·명령·소유권 검증, bounded CLI를 제공한다. 생성/시작 응답 유실은 운영자 확인을 유지하고, 삭제 응답 유실은 같은 엔진·전체 ID의 부재를 확인해 재개한다. 기록을 새 JVM으로 재개방하며 과거 중앙 marker/Run을 변경하지 않는다. `./gradlew test workerContainerIsolationTest` 성공: 신규 상태/장애 단위 18개를 포함한 루트 243개와 실제 Docker 검증 4개(I1 2개, 어댑터의 정지/실행 경계 회수 2개)를 재실행해 통과했다. 실패·오류·건너뜀 0이며 최종 Docker 조회에서 시험 컨테이너가 없었다. Career 모듈 코드/빌드는 변경하지 않았다. I2를 완료하며 다음은 I3 protocol transport와 중앙 executor 연결이다.

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

다음 실행 작업은 **D4-I3: 실제 Java 21 protocol transport와 중앙 executor 연결**이다. [I1–I5 설계](worker-container-isolation.md)에 따라 container lifecycle 어댑터를 protocol/evidence 경계에 연결한다. I2는 영속 소유권·생성/시작 미확인 차단·정확한 ID 회수를 제공하지만 중앙 marker 해제와 Run 복구는 아직 담당하지 않는다. sequence DB 소유자 분리와 종료 미확인 자동 복구는 I4/I5에서 진행한다.
