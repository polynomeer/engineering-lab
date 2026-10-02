# Experiment Platform 작업계획

설계 기준은 [Experiment Platform v2](experiment-platform-design.md)다. 기존 패키지 추출 계획은 core/metrics/세 실험군 도입까지 진행되었다. 이 문서는 이후 작업을 실행 증거 중심으로 재정렬한다.

각 단계는 검증 가능한 작업 단위로 나누고 `./gradlew test` → diff 검토 → Conventional Commit으로 완료한다. 다음 단계가 미완료인 상태를 플랫폼 전체 완성으로 보고하지 않는다.

## 1. 실행 모델 기반 — 완료

- [x] 현재 코드와 제안 문서의 차이를 확인하고 설계·이행 경계를 정리한다.
- [x] core에 실행 ID, 불변 파라미터, JVM/OS 환경 스냅샷을 추가한다.
- [x] 성공·실패 결과와 기존 jobId를 연결하고 generic/ingestion API에 additive field로 노출한다.
- [x] 실제 실행·실패·snapshot·API 회귀 테스트를 통과시킨다.

완료 기준: 세 실험군이 동일한 실행 메타데이터 모델을 사용하고 기존 UI/API가 유지된다. 첫 단계에서는 서버 재시작 후 결과 보존을 보장하지 않는다.

검증: `./gradlew test` 32개 통과. 파라미터 변경 후 원본 스냅샷 보존, 독립 실행 UUID, 실패 metadata, 큐/IO HTTP JSON, Excel 두 모드와 잘못된 workbook의 결과를 검증했다.

## 2. 실행 증거 영속화 — 완료

- [x] RunStore 계약, 별도 H2 file 어댑터와 v1 스키마를 추가했다. 재개방 후 결과 조회, 중복 metric 재전송, 페이지 경계, 미완료 실행 복구 테스트 통과. 런타임 기본값과 복구 절차는 [저장 가이드](run-storage.md)에 기록했다.
- [x] Git SHA/dirty/수집 실패 이유, Excel 설정·입력 checksum을 스냅샷으로 남긴다.
- [x] bounded metric writer의 배압, size/time/종료 flush, 동일 sequence 재시도와 실패 전파를 구현하고 테스트했다. 완료 summary에 queue depth/wait·write latency·retry/failure 카운터를 포함한다.
- [x] 작업 서비스의 in-memory 이력을 제거하고 저장소로 전환했다. 실제 Spring 앱 재시작 후 큐/Excel 결과 조회, 중단 복구, executor 거부 기록을 검증했다. `/runs` 페이지 조회를 제공한다.
- [x] metric writer의 버퍼 상한과 per-point 대비 batch 처리량 테스트를 추가하고 실행 조건·raw 결과를 build report에 기록한다. 이는 전체 ingestion 메모리/성능 보장을 뜻하지 않는다.

완료 기준: 실행 상태·parameters·환경·metrics·summary가 재시작 후 조회되고, 저장소 장애에서 silent loss가 없다. 스키마/어댑터, writer, 서비스 전환은 각각 테스트 후 별도 커밋한다.

검증: 전체 `./gradlew test` 45개 통과. 재시작·실패/거부·중복 방지·backpressure·time/size/shutdown flush·스키마/소유권·페이지/preview 상한을 검증했다. artifact 파일 등록/조회는 4–5단계에 남는다.

## 3. Registry와 선언적 Scenario — 완료

- [x] 세 실험을 명시적 registry에 등록했다. 중복 ID, 잘못된 파라미터 타입/범위, 미지원 옵션을 거부한다. [정의 v1 가이드](scenarios.md)를 참고한다.
- [x] Git 관리 JSON/YAML 예제와 합성 XLSX fixture, 가설·SINGLE_PASS 측정 조건·canonical 정의/hash를 추가했다.
- [x] `GET /experiments`, `GET /experiments/{id}`, JSON/YAML 및 Excel multipart `POST /runs`를 구현했다. 재실행 hash 일치와 invalid definition의 Run 미생성을 검증했다.
- [x] `./gradlew -q lab --args=...`의 list/validate/run(--wait) CLI를 구현하고 실제 서버에 세 예제를 제출해 완료를 검증했다.

완료 기준: 파일 하나로 같은 입력 조건을 재실행하고 잘못된 정의는 실행 전에 실패한다.

검증: 전체 `./gradlew test` 56개 통과. JSON/YAML hash 일치, 중복/미지원/타입·크기 거부, invalid Run 미생성, input checksum·경로 경계, 세 실험의 HTTP CLI 실행과 timeout/응답 제한을 검증했다.

## 4. Run 조회·비교 및 Finding — 완료

- [x] `/runs/ui`에서 registry 목록/상세, 영속 Run 목록/상세/비교를 제공한다. 기존 홈에서 연결하며 실험별 실행 화면은 유지한다.
- [x] 환경·입력·단위 차이 비교 API, 종료 Run NDJSON export와 사용자 제공 artifact 참조 metadata를 구현했다. [증거 가이드](run-evidence.md) 참조.
- [x] `docs/findings/`에 Run/export/artifact와 연결하고 증거·해석·한계를 분리하는 템플릿과 작성 순서를 추가했다.

완료 기준: 저장된 두 실행을 재시작 후 비교하고 결과 해석에서 실행 증거를 추적할 수 있다.

검증: 전체 `./gradlew test` 61개 통과. DB 재개방 뒤 비교/export, 페이지 전체 metric export, artifact 불변성·상한·입력 거부, 실제 HTTP UI/정적 자산/API 경로를 검증했다. 브라우저에서 빈 목록, 실제 두 Run의 선택→비교, 상세, 404 오류와 375px 레이아웃을 확인했다. 실제 서버를 재시작한 뒤 같은 비교 URL로 두 결과가 복원되는 것도 확인했다. 파일 수집/호스팅은 이 단계의 artifact 참조에 포함되지 않는다.

## 5. 관측과 외부 인프라 — 진행 중

- [x] `GET /metrics/schema`와 공통 단위·범위 catalog v1을 추가하고 비교 API에서 재사용한다. 기존 이름·저장 형식은 유지한다.
- [x] 선택적 JVM 시작/종료 스냅샷을 bounded writer로 저장한다. 기본 OFF, 최대 두 point/Run, 프로세스 범위와 수집 비용을 명시한다. [관측 가이드](observability.md) 참조.
- [x] 주기적 JVM sampling을 선택적으로 지원한다. 고정 지연, Run별 표본 상한, 종료 시 게시 drain과 실패 전파를 검증한다.
- [ ] semantic metric schema 확장 → 명시적 trace → 선택적 JFR/log 순으로 추가한다.
- 실험 요구가 생기면 Testcontainers 기반 DB/Redis/Kafka 시나리오를 별도 작업으로 도입한다.
- Java Agent는 플랫폼 필수 기능이 아닌 독립 실험으로 남긴다.

현재 검증: 전체 `./gradlew test` 71개 통과. 주기 수집의 표본 상한·배압 종료 순서·종료 timeout·비동기 실패와 실제 HTTP 설정 적용도 검증했다. JVM 수집 활성/비활성·실패·미지원 값·재개방·최소 preview와 세 실험 HTTP 실행을 확인했다. 수집 비용/크기 리포트는 `build/reports/observability/jvm-snapshot.json`에 생성한다. 5단계 전체는 아직 미완료다.

완료 기준: 각 관측 기능의 overhead·데이터 용량·종료 처리·민감정보 제외 정책을 검증한다. 인프라가 필요 없는 기존 테스트는 Docker 없이 실행 가능해야 한다.
