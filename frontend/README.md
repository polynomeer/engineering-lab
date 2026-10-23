# Lab Dashboard

기존 Lab API를 사용하는 독립 React + TypeScript + Vite 프론트엔드입니다.
현재 Run 목록·상세·비교·페이지 조회·NDJSON export·관리 artifact 다운로드를 지원합니다.
큐·IO 실행 폼과 기본 프리셋, 최근 설정 12개 저장·복원, 비교 요약 CSV/JSON도 지원합니다.
모듈/실험 목록과 입력 범위는 서버 catalog에서 가져옵니다. Excel 업로드·처리 현황·현재 DB 조회·IO 단건 데모도 지원합니다. 설정으로 등록된 Career sequence의 실행과 worker 환경·검증 결과 조회도 지원합니다.

## 준비와 실행

Node **24.19.0** (`.nvmrc`), npm **11.6.0**을 사용합니다. npm 설치 없이 고정 버전을 호출하려면 아래 `npx` 명령을 사용합니다. 시스템 Node 23은 지원 대상이 아닙니다.

저장소 루트에서 API를 실행합니다.

```bash
./gradlew bootRun
```

다른 터미널에서:

```bash
cd frontend
npx --yes npm@11.6.0 ci
npm run dev
```

`http://127.0.0.1:5173/app/runs`를 엽니다. **실험 실행**에서 큐·IO Run을 만들고 접수된 실행 상세로 이동합니다. Excel은 catalog에서 선택하고 DB·IO 도구는 왼쪽 링크를 이용합니다. 기존 API 서버는 기본 8080 포트를 사용합니다.

다른 API 주소는 LAB_API_TARGET으로 지정합니다.

```bash
LAB_API_TARGET=http://127.0.0.1:18080 npm run dev
```

`LAB_API_TARGET`은 서버 측 proxy 대상이고 브라우저 요청은 `/api/*`로 제한됩니다.
API 기본값은 `http://127.0.0.1:8080`입니다. 같은 빌드 산출물을 독립 배포와 내장 배포에 함께 사용합니다.

## 검증

```bash
npx --yes npm@11.6.0 ci
npm run typecheck
npm test
npm run build
```

브라우저 테스트는 한 명령으로 실행합니다. Playwright가 임시 저장소(`$TMPDIR/lab-dashboard-e2e-*`)를 쓰는 API(127.0.0.1:18080)와 Vite(127.0.0.1:4180)를 띄우고, 끝나면 종료합니다. 사용자 `.lab` DB는 건드리지 않습니다. 두 포트가 비어 있어야 합니다.

```bash
# frontend/에서 실행. Chromium 최초 설치가 필요할 수 있음
npx playwright install chromium
npm run test:e2e
```

포트는 `E2E_API_PORT`, `E2E_WEB_PORT`로 바꿉니다. API 포트를 바꾸면 기존 주소 전환 테스트용 `API_URL`(API 서버 origin, 기본 `http://127.0.0.1:18080`)도 함께 지정합니다. 직접 띄운 서버를 쓰려면 `DASHBOARD_URL=http://127.0.0.1:5173 npm run test:e2e`처럼 지정합니다. 이때 Playwright는 서버를 시작하지 않습니다. Career worker·Docker가 필요한 테스트는 해당 환경 변수가 없으면 건너뜁니다.

기본 screenshot은 무시되는 `test-results/`에 저장됩니다. 문서 캡처를 갱신할 때만
`DOC_SCREENSHOTS=1 npm run test:e2e`를 사용하고 변경 이미지를 검토합니다.

루트의 `./gradlew test`도 커밋 전 필수입니다. GitHub Actions(`.github/workflows/ci.yml`)가 push·PR마다 루트 테스트, 위 단위 검증, 브라우저 테스트를 실행합니다. Java 테스트만 실행할 때는 Node 설치가 필요하지 않습니다.

## 구조와 경계

- `src/api.ts`: API prefix, HTTP/JSON 오류, optional 404, route parsing, artifact 다운로드 조건
- `src/components.tsx`: 공통 표·상태·원시 데이터·Run 동작
- `src/main.tsx`: 앱 셸(부트스트랩 조회, 탐색 메뉴, 제목·상태, 경로별 화면 선택)
- `src/useRouteData.ts`: 경로별 Run 목록·상세·비교 조회, 활성 Run polling과 일시 오류 backoff
- `src/pages/RunHistory.tsx`, `src/pages/RunDetail.tsx`, `src/pages/Compare.tsx`: 실행 기록·Run 상세(결과 보기 전환)·Run 비교 화면
- `src/ExecutionForm.tsx`, `src/execution.ts`: schema 기반 폼, 프리셋 저장, 단일 제출과 비교 다운로드
- `src/LegacyTools.tsx`, `src/moduleViews.tsx`: 기존 업로드 API·Excel 상세 adapter, 현재 DB·비영속 IO 도구
- `src/settings.ts`, `src/SettingsPage.tsx`: bounded 설정 검증·이전·단일 key 저장, 선택적 conflict 처리
- `src/series.ts`, `src/SeriesPages.tsx`, `src/SweepPage.tsx`: 반복 측정·스윕 제출(`/run-series`, `/run-sweeps`), 묶음 목록·상세(진행 polling)·measure 분포 비교·기준선 판정, 스윕 차트·heatmap
- `src/DeadLetters.tsx`: 종료된 Excel Run의 dead-letter 행 페이지 조회
- `src/runInsights.ts`, `src/RunOverview.tsx`: 기록값 기반 핵심 지표·파이프라인 흐름·check/비교 요약 계산과 화면
- `src/WorkerEvidence.tsx`: 실행 준비 상태, worker 환경, 종료 후 검증 결과의 bounded 페이지 조회와 확장 내보내기
- `src/style.css`: Dark Observatory 차콜·앰버 토큰과 반응형 스타일
- `tests/dashboard.spec.ts`: 실제 서버 기반 browser E2E와 오류/cursor/legacy fallback
- `../contracts/dashboard/v1/fixtures/evidence.json`: Java HTTP 계약 테스트와 공유하는 합성 fixture

`/app/*`는 화면, `/api/*`는 backend proxy입니다. 다른 경로에 SPA HTML을 응답하지 않습니다.
proxy는 기존 status/body/Content-Disposition을 유지하고 상대 Location 헤더에 `/api`를 붙입니다.
capabilities/modules의 404는 구형 서버 호환으로 처리하며 503·HTML 응답 등은 오류로 표시합니다.
capabilities는 schemaVersion=1과 올바른 features 객체만 인식합니다. 알 수 없는 버전/구조는 기존 API 모드로 전환하고, 알려진 기능 키의 boolean true만 지원으로 해석합니다. 실행·조회는 기존 API를 유지하며, 명시적으로 지원되는 경우 실행 환경·검증 결과 패널과 확장 내보내기를 추가합니다.

활성 Run 상세는 2초 간격으로 이전 요청 완료 후 갱신합니다. 오류 backoff는 최대 15초이며,
탭 비활성·페이지 이탈·Run 종료 시 polling을 멈춥니다. 실행 증거도 자체 요청이 끝난 뒤 갱신하므로 느린 응답을 Run 목록 갱신이 반복 취소하지 않습니다. 관측값과 검증 결과는 각각 한 페이지만 보유합니다.
기존 화면 URL의 302 전환과 독립 서버·내장 패키징은 [배포 가이드](../docs/dashboard-deployment.md)를 따릅니다. 기존 Java 내장 화면은 제거했습니다.
새 설정은 `engineering-lab.dashboard.presets.v1`에 최근 12개만 보관하며 읽기는 1 MiB/100개로 제한합니다.
새 실험은 input=NONE, SINGLE_PASS, 정수/boolean/선택형 string schema일 때 추가 UI 분기 없이 실행 폼을 제공합니다.
POST 실패 시 자동 재시도하지 않습니다. 네트워크 오류·5xx·접수 응답 손상은 접수 불확실로 표시합니다.

`npm run build`는 `dist/`에 산출물을 만듭니다. `npm run preview`는 로컬 산출물 확인용이며 운영 서버가 아닙니다.

설정 백업/이전은 `/app/settings`를 사용합니다. v0.3.0까지의 기존 화면에서 내보낸 JSON(`engineering-lab-legacy` v1)과 같은 origin의 기존 key를 읽을 수 있으며 원본 key를 변경하지 않습니다. 기존 화면은 제거되어 새로 내보낼 수 없습니다. 상세 제한과 이전 절차는 [사용 가이드](../docs/standalone-dashboard.md)를 참고하세요.

독립 배포는 `npm run serve`를 사용합니다. 루트 `./gradlew bootJar`가 이 frontend를 빌드해 기본 jar에 내장합니다(`-PskipFrontend`로 제외). `npm run test:serve`는 빌드 후 독립 서버의 streaming/headers/실패 응답을 검증합니다. 대시보드를 내장했거나 `lab.dashboard.frontend-origin`을 지정한 API로 전체 브라우저 검증을 하면 `EXPECT_REDIRECT=1`(없으면 기존 주소가 404인지 확인), 산출물 일치 검증에는 `VERIFY_BUILD=1`을 지정합니다.

D3 호환 검증은 신규 API와 실제 D2 서버(`94c1913`)를 서로 다른 임시 DB로 띄워 수행했습니다. 신규 endpoint가 없는 구버전 API 서버를 검증할 때 `EXPECT_LEGACY=1`을 지정합니다. API 계약과 지원 범위는 [Run 증거 문서](../docs/run-evidence.md#지원-기능과-catalog-확장)를 참고하세요.

## Career sequence 브라우저 검증

[worker 배포 가이드](../docs/career-worker-deployment.md)에 따라 sequence를 활성화하고 Docker를 실행한 API를 사용합니다.

```bash
CAREER_E2E=1 npm run test:e2e -- tests/career.spec.ts
```

실제 BEFORE/ATOMIC_UPDATE 실행·비교·내보내기·모바일 폭·새로고침을 확인합니다. `CAREER_E2E`가 없으면 실제 Docker 실행 테스트를 건너뛰며, 준비 불가/부분 증거/오류/페이지 이동 테스트는 합성 응답으로 실행합니다. 문서 캡처는 `DOC_SCREENSHOTS=1`로 생성합니다. 준비 상태 조회는 최대 75초를 기다리고 자동 실행 재시도는 하지 않습니다. 확인 중이나 준비 불가일 때 입력과 실행을 잠그며, 재확인해도 작성한 설정을 유지합니다.

서버를 같은 저장소 경로로 재시작한 뒤에는 완료된 두 Run ID를 지정해 저장 증거의 브라우저 재조회를 검증합니다. 아래 값은 실제 ID로 바꿉니다.

```bash
CAREER_REOPEN_IDS=before-run-id,atomic-run-id npm run test:e2e -- --grep 'stored Career evidence after server restart'
```

이 검증은 정상 재시작 후 조회이며 강제 종료·Docker daemon 장애 복구를 검증한 것은 아닙니다.

## Dark Observatory 화면

[디자인 명세](../docs/dashboard-visual-design.md)를 적용했습니다. 좌측 catalog 탐색과 Run 상세의 전체·검증 결과·메트릭·실행 환경·아티팩트 보기를 제공합니다. Worker 증거는 데스크톱에서 검증 결과와 환경을 나란히, 모바일에서는 검증 목록과 환경을 한 열로 표시합니다. 모바일 메뉴는 접을 수 있고 Escape로 닫으면 메뉴 버튼에 focus가 돌아옵니다. check 집계는 현재 페이지 범위이며 전체 통과 수로 해석하지 않습니다.

`tests/design.spec.ts`는 결과 보기 전환 중 증거 유지, 모바일 메뉴 키보드 조작과 375/768/1024px 가로 넘침을 검증합니다. 캡처는 합성 응답을 사용하며 실제 측정값이 아닙니다.

2026-10-16 보완: CHECK_RESULTS는 검증 결과를 우선하며 IN_PROCESS 실행은 원본 메트릭을 주 결과로 보여줍니다. 환경 inspector에서 증거·재현·export를 분리했고 같은 실험의 최근 5개를 조회해 2개 Run 비교로 연결합니다. 관련 기록 조회 실패는 상세 조회와 분리합니다. 탐색은 고정 버전 Radix 아이콘을 사용합니다.

## 메트릭 그래프

메트릭 보기에서 실행 중에는 cursor 이후 관측을 약 2초 간격으로 읽어 처리량·큐 점유율 SVG 그래프를 갱신합니다. Pipeline은 기록된 구간 처리량과 Raw/Mapped 비율, Queue는 인접 누적 소비 건수로 계산한 구간 평균과 깊이/용량 비율, IO는 전체 요청 누적 평균을 사용합니다. 값·시간·용량이 없으면 결측으로 남기며 그래프를 합성하지 않습니다. slider와 값 표로 키보드 조회를 지원하고 원본 표·관측 cursor를 유지합니다. 최근 1,000개만 보유하며 중복 sequence를 제거합니다. 숨긴 탭에서는 조회를 멈추고 복귀하면 이어 읽습니다. 오류 시 기존 그래프를 유지하고 재시도하며, 종료 후 마지막 관측까지 읽으면 조회를 멈춥니다. 현재 관측 페이지 보기로 전환하면 원본 페이지를 확인할 수 있습니다. 계산과 제한은 [디자인 명세](../docs/dashboard-visual-design.md#12-메트릭-그래프)를 참고하세요.

`src/chartData.ts`는 단위와 계산·결측·시간 검증, `src/RunMetricCharts.tsx`는 그래프·표본 선택·값 표를 담당합니다. `chartData.test.ts`와 `tests/charts.spec.ts`가 reset·결측·용량 부재·한 점·페이지 이동·모바일 표시를 검증합니다.

## 패턴 탐색 (P2)

`/app/catalog`은 기존 실행·기록 화면에 연결되는 분야/패턴/프로젝트 출처 탐색이다.
`PatternExplorer.tsx`는 요청 lifecycle과 필터 UI, `patternCatalog.ts`는 v1 검증과
`/experiments` 기준 결합을 담당한다. 새 API 요청은 탐색 화면에서만 수행한다.
404/지원하지 않는 버전은 모듈 탐색, 5xx/네트워크/손상된 v1은 명시적 오류와 재시도다.
필터는 URL에 저장하며 기존 브라우저 저장 설정과 Run 데이터는 수정하지 않는다.

```bash
DASHBOARD_URL=http://127.0.0.1:5173 npm run test:e2e -- tests/patterns.spec.ts tests/dashboard.spec.ts tests/design.spec.ts tests/execution.spec.ts
```

`patterns.spec.ts`는 P1 정의와 16개 연결의 fixture에 합성 descriptor를 붙여 전체 링크·필터·오류를
검증한다. 실제 worker 13개 실행 검증은 아니다. 마지막 테스트는 연결된 실제 API를 사용한다.
구형 v0.1.0 API에 연결한 별도 frontend에서는
`EXPECT_PATTERN_LEGACY=1 npm run test:e2e -- tests/patterns.spec.ts --grep 'actual API'`로
404 fallback과 기존 deep link를 검증한다. 새 API에서 `PATTERN_SCREENSHOTS=1`을 지정하면
실제 기본 catalog의 문서 캡처를 갱신한다. 두 서버는 각각 임시 저장소를 사용한다.

## 전략·사례·시나리오 (P3)

`StudyDetails.tsx`는 독립 study API를 한 번 조회하고 탐색 카드에 설명/JSON 다운로드를 제공한다.
404/미지원 버전은 기존 탐색을 유지하고 다른 오류는 명시적으로 표시·재시도한다.
프리셋 JSON을 자동 실행하거나 기존 저장 설정에 덮어쓰지 않는다. `studies.test.ts`와
`tests/studies.spec.ts`가 계약·다운로드 동일성·오류 복구·폐기 표시를 검증한다.
`DASHBOARD_URL=<새 API에 연결한 frontend> npm run test:e2e -- tests/studies.spec.ts tests/patterns.spec.ts`
로 실행한다. 상세 모델과 상한은 [P3 계약](../docs/study-catalog.md)을 따른다.

## P6 선택 사례 재사용 검증

`lab.examples.checksum.enabled=true`로 실행한 임시 API에 frontend를 연결한 뒤 실행합니다.
기본 비활성 서버의 전체 UI suite에서는 이 선택 사례 테스트를 건너뜁니다.

```bash
CHECKSUM_E2E=1 DASHBOARD_URL=http://127.0.0.1:15178 API_URL=http://127.0.0.1:18095 \
  npm run test:e2e -- tests/checksum-extension.spec.ts tests/measurement.spec.ts tests/dashboard.spec.ts
```

`CHECKSUM_E2E=1`은 검증 대상 서버가 활성화됐다는 명시적 조건이며, 실제 활성화하지 않은 서버에서는
테스트가 실패해야 합니다. 추가 UI 분기 없이 패턴 탐색, 사례 설명, generic form의 세 시나리오,
기록/비교/export와 모바일 폭을 검증합니다. [참조 사례](../experiments/examples/batched-checksum/README.md)를 참고하세요.

### P7 버전 간 인수

기본 실험만 활성화한 서버와 전체 Career 서버에서 `patterns.spec.ts`, `studies.spec.ts`를
같이 실행할 수 있다. 활성 API의 실험 목록을 기준으로 탐색 결과를 대조한다.
`pattern-release.spec.ts`는 전체 16개 실험이 활성화되고 구형 Run 저장소를 복원한
서버에서만 실행한다. `PATTERN_HISTORY`는 전환 전 `/runs/{id}` 응답을 보존한 JSON 배열
(`[{"run": <원본 Run 응답>, ...}]`)의 경로다. 테스트는 Run을 새로 제출하거나 변경하지 않는다.

```bash
DASHBOARD_URL=http://127.0.0.1:4189 \
  PATTERN_HISTORY=../.lab/verification/v0.2.0/previous-history.json \
  npm run test:e2e -- pattern-release.spec.ts patterns.spec.ts studies.spec.ts
```

`P7_SCREENSHOTS=1`이면 실제 서버의 패턴·사례·모바일·구형 계약 화면을
`docs/images/26-pattern-release.png`, `27-pattern-release-mobile.png`,
`28-legacy-contract.png`로 갱신한다. API mock 회귀 캡처와 구분한다.
전체 Career 정상·의도적 실패·REPORT 다운로드는 `DEPLOYMENT_ACCEPTANCE`를 지정한
`local-deployment.spec.ts`와 [배포 인수 도구](../docs/local-deployment.md)를 함께 실행한다.
