# 독립·내장 대시보드 배포와 기존 주소 전환

2026-10-07 기준. 로컬 단일 사용자 환경을 대상으로 한다. API는 Java 25, 프론트엔드 빌드·독립 서버는 Node 24.19.0 / npm 11.6.0을 사용한다. Career worker 13개 시나리오의 선택적 등록·관측·다운로드·복구는 [통합 운영 가이드](dashboard-operations.md)를 따른다.

## 공통 빌드

`frontend/`에서 실행한다. 두 배포에서 같은 `dist/`를 사용하려면 `VITE_LEGACY_BASE`를 지정하지 않는다. 기본 기존 화면 링크는 현재 origin의 `/ui?legacy=true`이며 독립 서버가 API origin으로 연결해 준다.

```bash
npx --yes npm@11.6.0 ci
npm run build
npm test
npm run test:serve
```

실험 증거 `.lab` 및 ingestion DB를 사용하는 기존 API의 실행/중단 원칙은 기존 운영 문서를 따른다. UI 전환은 Run DB를 복사하거나 초기화하지 않는다.

## 독립 배포

루트에서 `./gradlew bootJar` 후 Java 25로 API를 시작한다. `java --version`으로 버전을 확인한다. macOS에서는 `/usr/libexec/java_home -v 25`로 해당 JDK 위치를 찾을 수 있다.

```bash
java -jar build/libs/excel-pipeline-0.0.1-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port=8080
```

다른 터미널의 `frontend/`에서:

```bash
LAB_API_TARGET=http://127.0.0.1:8080 PORT=4173 npm run serve
```

`http://127.0.0.1:4173/app/runs`를 연다. 배포에 필요한 프론트 파일은 `dist/`, `server/serve.mjs`이며 `node server/serve.mjs`로도 실행한다. 추가 런타임 npm 의존성은 없다. Vite dev/preview를 배포 서버로 사용하지 않는다.

- `PORT`: 기본 4173, 항상 127.0.0.1에 bind.
- `LAB_API_TARGET`: 기본 `http://127.0.0.1:8080`, 경로·인증정보 없는 HTTP(S) origin. 브라우저는 `/api/*`에 요청하고 서버가 prefix를 제거한다.
- multipart 요청과 NDJSON/파일 응답은 스트림으로 전달한다. 전체 body를 메모리에 모으지 않는다. upstream 무응답 제한은 120초이며 연결 실패는 JSON 502다.
- 상태·Content-Type·Content-Disposition은 유지하고 같은 API origin의 Location을 `/api` 경로로 바꾼다.
- `/app`의 화면 새로고침은 index.html, `/app/assets/*`는 실제 파일만 제공한다. 없는 asset/API를 HTML 성공으로 가리지 않는다. `/ui`는 기존 API 홈의 `legacy=true` 주소로 연결한다. 그 외 경로는 404다.

## 내장 배포

같은 `frontend/dist`를 빌드한 상태에서 루트에서 실행한다.

```bash
./gradlew bootDashboardJar
java -jar build/libs/excel-pipeline-0.0.1-SNAPSHOT-dashboard.jar \
  --server.address=127.0.0.1 --server.port=8080
```

`http://127.0.0.1:8080/app/runs`를 연다. `bootDashboardJar`는 빌드된 파일을 `BOOT-INF/classes/static/app/`에 넣는다. dist가 없으면 실패하며 오래된 dist를 자동 재빌드하지 않으므로 소스 변경 후 공통 빌드를 먼저 수행한다.

기본 `test`, `bootJar`, `bootRun`에는 Node나 frontend 빌드를 강제하지 않는다. 기본 jar에는 SPA가 없으므로 `/app/*` 화면은 404다. `/api` adapter는 기존 API를 내부 servlet dispatch로 호출해 multipart/async streaming과 에러를 유지한다. 임의 서버로 프록시하거나 Spring 컨트롤러를 복제하지 않는다.

## 기존 주소 전환과 되돌리기

기본값:

```yaml
lab:
  dashboard:
    redirect-enabled: false
    frontend-origin: ""
```

전환을 원하는 API 실행에 다음 옵션을 추가한다.

```bash
# 독립 배포: API의 실행 옵션
--lab.dashboard.redirect-enabled=true \
--lab.dashboard.frontend-origin=http://127.0.0.1:4173

# 내장 배포: origin은 빈 값인 기본값 유지
--lab.dashboard.redirect-enabled=true
```

frontend-origin은 경로/query/fragment/사용자 정보 없는 HTTP(S) origin만 받는다. 빈 값이면 현재 origin의 `/app`을 사용하므로 내장 jar가 필요하다. 설정 변경은 API를 재시작해야 한다.

| 기존 GET 화면 | 새 화면 |
| --- | --- |
| `/`, `/ui` | `/app/runs` |
| `/experiments/ui` | `/app/experiments` |
| `/experiments/ui/{id}`, `/ingest/ui/{id}` | `/app/runs/{id}` |
| `/ingest/ui` | `/app/experiments/pipeline.excel-ingestion` |
| `/ingest/ui/db` | `/app/tools/ingestion-db` |
| `/lab/io/ui` | `/app/tools/io-echo` |
| `/runs/ui?run={id}` | `/app/runs/{id}` |
| `/runs/ui?baseline={a}&candidate={b}` | `/app/compare?baseline={a}&candidate={b}` |
| `/runs/ui?experiment={type}&before={cursor}` | `/app/runs?experiment={type}&before={cursor}` |

전환은 no-store 302이고 나머지 query 값은 보존한다. `run`은 경로로 옮기며 `legacy` 제어값은 전달하지 않는다. API와 POST는 화면 전환 대상이 아니다. 임의 query로 redirect 목적지 origin을 바꿀 수 없다.

되돌리려면 `redirect-enabled=false`로 API를 재시작한다. 파일/DB 변경은 필요 없다. 전환 중에도 `/experiments/ui?legacy=true`, `/lab/io/ui?legacy=true`, `/runs/ui?legacy=true`로 기존 화면과 **설정 내보내기**에 접근한다. 이 우회는 현재 화면에만 적용하며 다른 기존 링크를 누르면 전환 설정이 다시 적용된다.

## 검증과 진단

먼저 `/api/experiments`가 JSON 200인지, `/app/runs`가 HTML인지 확인한다. 404라면 기본 jar와 내장 jar를 혼동하지 않았는지 확인한다. 독립 서버의 502는 API 주소·포트·기동 상태를 확인한다. `UnsupportedClassVersionError`는 Java 25 대신 다른 Java로 실행한 경우다.

전용 임시 저장소를 지정해 브라우저 테스트를 실행한다. 두 API를 동시에 테스트할 때 같은 저장소를 공유하지 않는다.

```bash
# frontend/: 독립 서버가 14173, API가 18080인 예
DASHBOARD_URL=http://127.0.0.1:14173 VERIFY_BUILD=1 npm run test:e2e

# 내장 서버가 18081이고 전환을 켠 예
DASHBOARD_URL=http://127.0.0.1:18081 \
LEGACY_URL='http://127.0.0.1:18081/experiments/ui?legacy=true' \
EXPECT_REDIRECT=1 VERIFY_BUILD=1 npm run test:e2e
```

`VERIFY_BUILD=1`은 응답 JS가 현재 dist의 파일과 byte 단위로 같은지 확인한다. 같은 실행·Excel multipart·NDJSON export·설정 이전 테스트와 URL/query/legacy 우회 테스트를 양쪽에 사용한다. 루트 `./gradlew test`도 필수다. 종료는 실행 터미널의 Ctrl-C를 사용하고 진행 중 Run의 drain 시간을 기다린다.
