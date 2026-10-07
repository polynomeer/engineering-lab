# Lab Dashboard

기존 Lab API를 사용하는 독립 React + TypeScript + Vite 프론트엔드입니다.
현재 Run 목록·상세·비교·페이지 조회·NDJSON export·관리 artifact 다운로드를 지원합니다.
큐·IO 실행 폼과 기본 프리셋, 최근 설정 12개 저장·복원, 비교 요약 CSV/JSON도 지원합니다.
모듈/실험 목록과 입력 범위는 서버 catalog에서 가져옵니다. Excel 업로드·처리 현황·현재 DB 조회·IO 단건 데모도 지원합니다. Career worker 연결은 후속 단계입니다.

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

다른 API 주소는 LAB_API_TARGET으로 지정합니다. VITE_LEGACY_BASE는 필요할 때만 기존 화면 링크 주소를 덮어씁니다.

```bash
LAB_API_TARGET=http://127.0.0.1:18080 \
VITE_LEGACY_BASE=http://127.0.0.1:18080 npm run dev
```

`LAB_API_TARGET`은 서버 측 proxy 대상이고 브라우저 요청은 `/api/*`로 제한됩니다.
`VITE_LEGACY_BASE`는 기존 화면 링크용 공개 주소이며 비밀값을 넣지 않습니다.
API 기본값은 `http://127.0.0.1:8080`입니다. VITE_LEGACY_BASE를 생략하면 현재 origin의 `/ui?legacy=true`를 사용하고 독립 서버가 API 홈에 연결합니다. 같은 빌드 산출물을 두 방식으로 배포할 때는 생략하세요.

## 검증

```bash
npx --yes npm@11.6.0 ci
npm run typecheck
npm test
npm run build
```

브라우저 테스트는 **실행 중인 API와 frontend**가 필요합니다. 전용 임시 DB를 사용하세요. 테스트는 실제 Run을 생성하고 보존합니다.

```bash
# 저장소 루트의 별도 터미널: 사용자 DB 대신 임시 DB
./gradlew bootRun --args='--server.port=18080 --server.address=127.0.0.1 --lab.storage.root=/tmp/lab-dashboard-e2e'

# frontend/의 별도 터미널
LAB_API_TARGET=http://127.0.0.1:18080 VITE_LEGACY_BASE=http://127.0.0.1:18080 npm run dev

# frontend/ 테스트 터미널: Chromium 최초 설치가 필요할 수 있음
npx playwright install chromium
npm run test:e2e
```

다른 frontend 주소는 `DASHBOARD_URL=http://127.0.0.1:4173 npm run test:e2e`로 지정합니다.
기본 screenshot은 무시되는 `test-results/`에 저장됩니다. 문서 캡처를 갱신할 때만
`DOC_SCREENSHOTS=1 npm run test:e2e`를 사용하고 변경 이미지를 검토합니다.

루트의 `./gradlew test`도 커밋 전 필수입니다. CI에서도 전용 API 저장소/포트로 위 순서를 수행하고 브라우저 테스트 후 프로세스를 종료합니다. Java 테스트만 실행할 때는 Node 설치가 필요하지 않습니다.

## 구조와 경계

- `src/api.ts`: API prefix, HTTP/JSON 오류, optional 404, route parsing, artifact 다운로드 조건
- `src/components.tsx`: 공통 표·상태·원시 데이터·Run 동작
- `src/main.tsx`: catalog 기반 탐색, Run/비교 화면, abort 가능한 조회·활성 Run polling
- `src/ExecutionForm.tsx`, `src/execution.ts`: schema 기반 폼, 프리셋 저장, 단일 제출과 비교 다운로드
- `src/LegacyTools.tsx`, `src/moduleViews.tsx`: 기존 업로드 API·Excel 상세 adapter, 현재 DB·비영속 IO 도구
- `src/settings.ts`, `src/SettingsPage.tsx`: bounded 설정 검증·이전·단일 key 저장, 선택적 conflict 처리
- `src/style.css`: 기존 Run 화면의 어두운 토큰과 반응형 스타일
- `tests/dashboard.spec.ts`: 실제 서버 기반 browser E2E와 오류/cursor/legacy fallback
- `../contracts/dashboard/v1/fixtures/evidence.json`: Java HTTP 계약 테스트와 공유하는 합성 fixture

`/app/*`는 화면, `/api/*`는 backend proxy입니다. 다른 경로에 SPA HTML을 응답하지 않습니다.
proxy는 기존 status/body/Content-Disposition을 유지하고 상대 Location 헤더에 `/api`를 붙입니다.
capabilities/modules의 404만 구형 서버 호환으로 처리하며 503·HTML 응답 등은 오류로 표시합니다.

활성 Run 상세는 2초 간격으로 이전 요청 완료 후 갱신합니다. 오류 backoff는 최대 15초이며,
탭 비활성·페이지 이탈·Run 종료 시 polling을 멈춥니다. 관측값은 한 페이지만 보유합니다.
기존 URL의 선택적 302와 독립 서버·내장 패키징은 [배포 가이드](../docs/dashboard-deployment.md)를 따릅니다. 기본값은 기존 화면 유지입니다.
새 설정은 `engineering-lab.dashboard.presets.v1`에 최근 12개만 보관하며 읽기는 1 MiB/100개로 제한합니다.
새 실험은 input=NONE, SINGLE_PASS, 정수/boolean/선택형 string schema일 때 추가 UI 분기 없이 실행 폼을 제공합니다.
POST 실패 시 자동 재시도하지 않습니다. 네트워크 오류·5xx·접수 응답 손상은 접수 불확실로 표시합니다.

`npm run build`는 `dist/`에 산출물을 만듭니다. `npm run preview`는 로컬 산출물 확인용이며 운영 서버가 아닙니다.

설정 백업/이전은 `/app/settings`를 사용합니다. 구화면 내보내기 스크립트는 `src/main/resources/static/dashboard/export-settings.js`에 있고 원본 key를 변경하지 않습니다. 상세 제한과 이전 절차는 [사용 가이드](../docs/standalone-dashboard.md)를 참고하세요. 브라우저 E2E의 구화면 주소는 `LEGACY_URL`(기본 `http://127.0.0.1:18080/experiments/ui`)로 변경합니다.

독립 배포는 `npm run serve`, 내장 패키징은 루트 `./gradlew bootDashboardJar`를 사용합니다. `npm run test:serve`는 빌드 후 독립 서버의 streaming/headers/실패 응답을 검증합니다. 전체 브라우저 검증에 URL 전환을 켠 API를 쓰면 `EXPECT_REDIRECT=1`, 산출물 일치 검증에는 `VERIFY_BUILD=1`을 지정합니다.
