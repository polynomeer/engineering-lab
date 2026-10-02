# Experiment Platform 작업계획

설계 기준은 [Experiment Platform v2](experiment-platform-design.md)다. 기존 패키지 추출 계획은 core/metrics/세 실험군 도입까지 진행되었다. 이 문서는 이후 작업을 실행 증거 중심으로 재정렬한다.

각 단계는 검증 가능한 작업 단위로 나누고 `./gradlew test` → diff 검토 → Conventional Commit으로 완료한다. 다음 단계가 미완료인 상태를 플랫폼 전체 완성으로 보고하지 않는다.

## 1. 실행 모델 기반 — 진행 중

- [x] 현재 코드와 제안 문서의 차이를 확인하고 설계·이행 경계를 정리한다.
- [ ] core에 실행 ID, 불변 파라미터, JVM/OS 환경 스냅샷을 추가한다.
- [ ] 성공·실패 결과와 기존 jobId를 연결하고 generic/ingestion API에 additive field로 노출한다.
- [ ] 실제 실행·실패·snapshot·API 회귀 테스트를 통과시킨다.

완료 기준: 세 실험군이 동일한 실행 메타데이터 모델을 사용하고 기존 UI/API가 유지된다. 첫 단계에서는 서버 재시작 후 결과 보존을 보장하지 않는다.

## 2. 실행 증거 영속화 — 예정

- RunStore 계약, H2 file 저장소, 버전 관리 스키마, 저장 기본값·문서를 추가한다.
- Git SHA/dirty/수집 실패 이유, Excel 설정·입력 checksum을 스냅샷으로 남긴다.
- bounded metric writer의 배압, size/time flush, 재시도·중복 방지·실패 보고를 구현한다.
- 작업 서비스의 in-memory 이력을 페이지 조회 저장소로 전환하고 재시작·중단 복구를 검증한다.
- 메모리/처리량 측정은 재현 조건과 raw 결과를 함께 보관한다.

완료 기준: 실행 상태·parameters·환경·metrics·summary가 재시작 후 조회되고, 저장소 장애에서 silent loss가 없다. 스키마/어댑터, writer, 서비스 전환은 각각 테스트 후 별도 커밋한다.

## 3. Registry와 선언적 Scenario — 예정

- 세 실험을 명시적 registry에 등록하고 중복 ID·미지원 옵션을 거부한다.
- Git으로 관리하는 JSON/YAML 시나리오와 가설·측정 조건·정의 해시를 도입한다.
- registry 기반 조회/실행 API와 CLI 진입점을 추가한다.

완료 기준: 파일 하나로 같은 입력 조건을 재실행하고 잘못된 정의는 실행 전에 실패한다.

## 4. Run 조회·비교 및 Finding — 예정

- 실험 목록/상세, run 상세/비교 화면을 기존 UI에서 점진적으로 이행한다.
- 환경·입력·단위 차이를 표시하고 결과 export와 artifact 참조를 제공한다.
- `docs/findings/`에 증거와 해석을 분리하는 문서 템플릿을 추가한다.

완료 기준: 저장된 두 실행을 재시작 후 비교하고 결과 해석에서 실행 증거를 추적할 수 있다.

## 5. 관측과 외부 인프라 — 예정

- metric schema/단위 정리 → JVM metrics → 명시적 trace → 선택적 JFR/log 순으로 추가한다.
- 실험 요구가 생기면 Testcontainers 기반 DB/Redis/Kafka 시나리오를 별도 작업으로 도입한다.
- Java Agent는 플랫폼 필수 기능이 아닌 독립 실험으로 남긴다.

완료 기준: 각 관측 기능의 overhead·데이터 용량·종료 처리·민감정보 제외 정책을 검증한다. 인프라가 필요 없는 기존 테스트는 Docker 없이 실행 가능해야 한다.
