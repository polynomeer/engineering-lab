# 패턴 중심 플랫폼 작업 계획

기준: 2026-10-17. [설계](pattern-platform-design.md)의 계약과 기존 v0.1.0 호환성을 유지한다.
각 단위는 구현·관련 회귀·전체 루트 테스트·diff 검토 후 독립 Conventional Commit으로 완료한다.

| 단계 | 상태 | 작업 및 산출물 | 완료 기준 |
| --- | --- | --- | --- |
| P0 | 완료 | 개념·16개 실험 지도·호환/측정/배포 원칙 문서화 | 설계와 단계별 검증 기준 연결 |
| P1 | 완료 | 불변 분류 metadata, 확장 등록, 유효성/상한 검사, 읽기 전용 pattern-catalog API | 기본/전체/미분류 catalog 및 오류·HTTP·기존 전체 회귀 통과 |
| P2 | 완료 | 분야·패턴 탐색, 프로젝트 출처 필터, 기존 URL과 새 API 없는 서버 fallback | 구형/신형 서버 브라우저 회귀, 16개 실험 접근 가능 |
| P3 | 완료 | Strategy/Case study/Scenario 관계, 프리셋·설명 등록 계약, 패턴 의미 버전/폐기 정책 | 기존 JSON/hash 불변; BEFORE/AFTER의 구체적 전략·사례 근거 표시 |
| P4 | 대기 | 공통 검증 성질·측정 범위·비교 가능 조건 모델 | 단위/환경/측정 범위 불일치 비교를 경고; 구형 증거 fallback |
| P5 | 대기 | Excel/Equity 파이프라인 공통 계약과 최소 재사용 코드 추출 | 기존 처리 결과·메모리 상한·backpressure·오류·재시도·종료 보존 |
| P6 | 대기 | 추가 사례로 패턴 재사용 검증 및 확장 가이드 갱신 | 중앙 controller/frontend 분기 없이 등록; 출처·한계 유지 |
| P7 | 대기 | 전체 인수·문서/캡처·릴리스·운영 전환 | 기본/Career 정상·실패·재시작·artifact 호환, 기존 Run 보존, 백업 검증 |

## P1 범위와 검증 단위

1. 7개 분야와 현재 16개 실험의 다중 패턴 연결을 구현한다.
2. 신뢰된 Spring contribution으로 정의를 추가하고, 현재 ExperimentCatalog와 결합한다.
3. 정의는 제공하되 활성 실험만 결과 목록에 포함한다. 미분류 확장 실험을 차단하지 않는다.
4. 기존 endpoint·descriptor·scenario·RunStore·worker·frontend를 바꾸지 않고 신규 endpoint만 제공한다.
5. 검증은 불변성/정렬/상한/중복/누락 참조, 기본 3개/전체 16개/추가 provider, HTTP 직렬화,
   기존 시나리오 실행·Run 재개방·export 회귀와 전체 `./gradlew test`로 수행한다.

P1 종료 시 화면은 기존 모듈 탐색을 유지한다. 코드 이동·범용 실행 엔진·DB migration·
운영 배포 교체는 이번 첫 단계의 완료 조건이 아니다. 후속 단계는 앞 단계의 검증 완료 후 착수한다.

## P1 완료 기록 — 2026-10-17

- `registry.pattern`에 불변 분류·contribution·검증 catalog를 구현했다.
- 읽기 전용 `GET /dashboard/v1/pattern-catalog`과 API/확장 가이드를 추가했다.
- 기본 3개/전체 provider 16개, 미분류 확장, Spring 추가 등록, 중복·참조·상한 검증을 통과했다.
- 기존 API 응답과 시나리오 hash 보존, Queue/Excel 저장 기록의 애플리케이션 재시작 후
  동일성을 확인했다. 과거 바이너리를 직접 구동하는 버전 간 업그레이드 인수 검증은 P7에 남긴다.
- Java 25에서 `./gradlew test`: 395개, 실패 0, 오류 0, 건너뜀 0.
  기존 저장 schema migration/export 회귀를 포함한다. Career 구현/독립 빌드는 변경하지 않았다.
- 기존 화면과 운영 v0.1.0은 유지했다. 다음 구현 단위는 P2의 패턴 탐색 및 구형 API fallback이다.

## P2 완료 기록 — 2026-10-17

- `/app/catalog`에 대표 분야·다중 패턴·프로젝트 출처 필터, 패턴 설명과 미분류 탐색을 추가했다.
  GET 폼 URL을 사용해 새로고침·뒤로가기·직접 링크를 지원하며 기존 실행/기록 URL을 유지한다.
- 404와 지원하지 않는 버전은 모듈 탐색으로 전환한다. 5xx·네트워크·잘못된 v1 응답은
  오류와 재시도를 제공한다. 부가 API는 기존 화면 초기화와 분리한다.
- 전체 16개 연결의 브라우저 회귀에서 비동기 descriptor 로딩 후 실행 선택값이 첫 항목으로
  바뀌는 기존 문제를 발견해, URL에 지정한 실험이 선택되도록 보완했다.
- 프런트엔드 단위 테스트 32개, typecheck/build 통과. 신규/기존 catalog·실행 기록·비교·내보내기·
  모바일 회귀 14개, Queue/IO 실행·프리셋/접수 불확실성 회귀 2개 통과.
- 별도 임시 API에서 기본 실험을 조회하고, 고정 v0.1.0 `controller.jar`를 별도 포트·저장소로
  실행해 실제 404 fallback/deep link 1개를 통과했다. 실제 Career worker 재실행은 이 단계의 범위가 아니다.
- 375/768px·가로 화면·키보드·reduced-motion·200% 텍스트 크기를 확인하고 실제 API 캡처를
  [사용 가이드](standalone-dashboard.md)에 추가했다. 루트 `./gradlew test` 통과(395개 결과 재사용, UP-TO-DATE).
- 다음 단계는 P3의 Strategy/Case study/Scenario 등록 계약이다. 운영 배포는 교체하지 않았다.

## P3 완료 기록 — 2026-10-17

- [전략·사례·시나리오 계약](study-catalog.md)을 작성하고 `StudyContribution` 확장 bean과
  `/dashboard/v1/study-catalog`를 구현했다. 기존 16개 실험과 40개 시나리오를 연결했다.
- 구체적인 BEFORE/AFTER 구현과 입력·경쟁·실패 조건을 구분했다. IO의 실제 구현 확인에 따라
  설명을 virtual-thread sleep / Reactor delay 합성 비교로 정정했다.
- 원본 시나리오를 classpath에 패키징하고 기존 parser로 canonical JSON/hash를 생성한다.
  기존 실행 정의·ID·Run 저장·worker 계약은 변경하지 않았다.
- 패턴 의미 버전과 ACTIVE/DEPRECATED·대체 후보를 등록하고 누락 참조·순환을 거부한다.
  과거 Run을 수정하거나 대체 후보로 자동 치환하지 않는다.
- 탐색 카드에서 근거·전략·시나리오와 JSON 다운로드를 제공한다. 구형 API와 오류는 기존 탐색을
  유지한다. 정의 저장은 자동 실행/폼 적용과 구분한다.
- 루트 테스트 400개(실패/건너뜀 0), frontend 단위 33개와 typecheck/build 통과.
  P2/P3 브라우저 14개: 전체 16개 링크·필터·fallback, 실제 정의 다운로드·모바일·폐기 표시 확인.
- Career의 소스/빌드·실제 worker 실행·운영 배포는 변경하지 않았다. 다음 단계는 P4의
  공통 검증 성질·측정 범위·비교 가능 조건 모델이다.
