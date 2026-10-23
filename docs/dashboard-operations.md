# Dashboard 운영과 D6 검증

## 작업 단위

1. 관리 REPORT 다운로드와 구/신 화면 호환. 기존 worker-reports 사본을 사용하고
   별도 DB 이행 없이 기존 JFR/log 검증 경계를 확장한다.
2. worker 관측 capability, workspace 용량 접수 제한과 경계 검증.
3. 전체 회귀·운영 명령·캡처·전환 parity 확인.

## 관리 artifact 다운로드

`GET /runs/{runId}/artifacts/{artifactId}/download`는 terminal Run의 관리 사본만 제공한다.
JFR(`jfr_<uuid>.jfr`), log(`log_<uuid>.ndjson`), REPORT(`worker_<uuid>.bin`)의
ID·디렉터리·media type을 검증한다. worker REPORT는 이미 중앙으로 이관된
`worker-reports/` 파일을 사용하므로 이전 Run도 다운로드할 수 있다.
REPORT 내용은 JSON일 수 있지만 protocol은 REPORT 바이트만 보장하므로
`application/octet-stream`과 attachment로 전달한다. 원본 worker 경로를 열지 않는다.

파일과 상위 디렉터리의 symlink를 거절하고 크기·SHA-256을 확인한 private snapshot을
8 KiB 버퍼로 전송한다. 검증 후 원본이 변경되어도 전송 내용은 바뀌지 않는다.
임의 등록 참조는 계속 참조 전용이다. 파일 누락 404, 변경/경로 불일치 409,
크기 초과 413, 동시 다운로드 초과 429를 반환한다. 제한은 기존
`lab.artifacts.download.max-bytes` / `max-concurrent`를 따른다.

신 frontend는 `/dashboard/v1/capabilities`의 `workerReportDownload=true`일 때만
REPORT 링크를 표시한다. 구 서버에서는 참조 전용으로 유지한다. `/runs/ui/options`
응답에도 `workerReportDownload=1`이 있다. 기존 API 경로·참조
스키마와 JFR/log 다운로드는 유지한다.

## 관측 범위와 용량 예산

각 실험의 `/dashboard/v1/experiments/{id}` capability가 관측의 기준이다. IN_PROCESS는
설정된 JVM/Trace/JFR/Run log를 수집한다. LOCAL_JVM/CONTAINER는 이 네 capability가
false이며 controller 관측값을 worker 관측값으로 대체하지 않는다. worker 환경은
HELLO metadata이고, memory 실험의 sampled heap은 실험 자체 metric이다. stderr는
최대 1 MiB 진단 꼬리/전체 byte count로 보관하며 다운로드 가능한 Run log가 아니다.
현재 worker protocol의 관리 artifact는 REPORT만 지원한다.

`lab.worker-storage.max-bytes=1073741824` (1 GiB), `reservation-bytes=134217728`
(128 MiB), `max-entries=100000`을 기본으로 사용한다. controller의 `worker-workspaces/`
및 `worker-reports/` 보존 파일을 합산하고 실행마다 여유 공간을 예약한다. 두 디렉터리의
합과 예약이 예산을 초과하거나 symlink/특수파일/32단계 깊이/항목 수 제한을 만나면
접수를 거절한다. 실행 직전에도 재검사하고 종료·실패 시 예약만 해제한다. 재시작하면
디스크를 다시 합산한다. 보존 증거나 복구 journal은 자동 삭제하지 않는다.

이는 **접수 예산**이며 실행 중 파일 쓰기를 가로채는 파일시스템 hard quota가 아니다.
H2, 업로드, JFR/log, Docker DB tmpfs, 다운로드 snapshot은 이 합계 밖이다. 외부 쓰기나
LOCAL_JVM의 임의 출력까지 제한해야 하는 배포는 별도 볼륨 quota를 설정해야 한다.
운영자는 앱을 중지하고 전체 저장소를 백업한 다음 완료 Run 증거의 보존 정책을 적용한다.
복구 미확인 workspace와 active marker를 임의로 지우지 않는다.

경계 검증은 `WorkerStorageBudgetTest`에서 실제 파일 4,096 byte에 2,048 byte 예약
두 개를 더해 8,192 byte 경계를 채우고 세 번째 접수를 거절한다. 파일 2,049 byte를
추가하면 재시작 후에도 예약을 거절한다. 동시 예약과 중복 release, 항목 수·symlink·
초과 파일을 검증하며 원본 파일을 삭제하지 않는다.

## 실행·전송·조회 경계

| 경계 | 기본 상한과 검증 |
| --- | --- |
| 실행 슬롯 | `lab.storage.max-active-runs=8`; worker는 exclusive, 실행기별 1. 다른 실행과 겹치지 않는다. ExecutionAdmissionTest는 전역 2/실행기 1·exclusive 양방향 충돌과 해제를 검사한다. |
| worker 시간 | 15분 deadline, 정리 30초. WorkerProcess/ContainerWorker 회귀에서 낮춘 deadline으로 종료와 부분 증거 보존을 확인한다. Docker 개별 요청의 제한과 합산 wall time은 동일하지 않다. |
| wire | record 64 KiB, total 64 MiB, events 100,000. 초과는 실패이며 잘라서 성공 처리하지 않는다. |
| REPORT 이관 | 파일당 16 MiB, 합계 64 MiB, 32개; 크기/hash/중복/path 검사, 8 KiB 복사 버퍼. |
| 다운로드 | 기본 파일당 256 MiB, 동시 2; snapshot 디스크 최대 512 MiB, 전송 버퍼 8 KiB/요청. 클라이언트 실패에도 slot/snapshot 해제. |
| metric 배압 | writer queue 1,000, batch 100, preview 200. 테스트는 queue 2에서 4번째 생산자가 대기하고 4건 모두 저장되는지 확인한다. |
| 조회 | metric/run page 설정 500, UI 20(최대 100); export 페이지 min(설정,100), CHECK min(설정,100), 최대 100,000 checks/Run. UI는 다음 페이지로 교체하며 전체 이력을 누적하지 않는다. |
| 컨테이너 | worker 256 MiB/1 CPU/64 PID, /tmp 16 MiB; DB/Redis는 모듈별 tmpfs·memory 경계. LOCAL_JVM은 동일 OS 격리를 제공하지 않는다. |

위 숫자는 설정/프로토콜 경계다. 작은 제어된 fixture로 차단·배압·페이지 교체를 재현한
결과를 모든 하드웨어의 처리량·RSS 최대값 또는 부하 SLA로 확대 해석하지 않는다.

## 빌드와 시작

Java 25 controller, Java 21 Career, Node 24.19.0/npm 11.6.0 frontend를 사용한다.
루트와 Career wrapper는 공유 worker-support 때문에 순차 실행한다.

```bash
./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest \
  :mcp-platform:checksWorkerManifest :equity-system:equityWorkerManifest \
  :batch-excel:memoryWorkerManifest :mds-distribution:mdsWorkerManifest \
  :creator-studio:creatorWorkerManifest
./gradlew test bootJar
(cd frontend && npm ci && npm test && npm run build && npm run test:serve)
```

각 manifest의 jar를 Java 21 JRE 이미지 `/opt/lab/worker.jar`에 넣는다. 이미지 계약은
[컨테이너 가이드](worker-container-isolation.md)를 따른다. image ID는
`docker image inspect <tag> --format '{{.Id}}'` 전체값, jar hash는 manifest의
artifactSha256을 사용한다. 최신 tag 이름만 서버 설정에 넣지 않는다.

| 모듈 설정 | 실행 문서 |
| --- | --- |
| sequence (CONTAINER 선택) | [sequence](career-worker-deployment.md) |
| architecture/validation | [checks](mcp-checks-worker.md) |
| equity 세 실험 | [equity](equity-worker.md) |
| batch/excel memory | [memory](batch-memory-worker.md) |
| MDS 두 실험 | [MDS](mds-worker.md) |
| Creator 세 실험 | [Creator](creator-worker.md) |

모듈 문서의 opt-in/image/hash 설정을 관리자 전용 `application-career.yml`에 작성한 뒤:

```bash
java -jar build/libs/engineering-lab-0.0.1-SNAPSHOT.jar \
  --spring.config.additional-location=file:./application-career.yml \
  --server.address=127.0.0.1 --server.port=8080 --lab.storage.root=./.lab
# 다른 터미널
cd frontend
LAB_API_TARGET=http://127.0.0.1:8080 PORT=4173 npm run serve
```

`/app/experiments`에서 활성화한 모듈만 나타난다. 모든 Career 설정은 기본 비활성이며,
설정 파일을 지정하지 않으면 기존 세 실험군만 실행한다. 내장 SPA는 기본
`./gradlew bootJar`에 포함된다(Node 필요). [배포 가이드](dashboard-deployment.md)의
두 방식을 혼용해 같은 저장소를 동시에 열지 않는다.

## 정지·재시작·readiness 진단

1. 실행 중인 terminal에서 Ctrl-C/SIGTERM으로 frontend와 API를 정지한다. API는
   새 접수를 중단하고 실행·writer 정리를 기다린다. SIGKILL은 복구 검증 외에는 사용하지 않는다.
2. 동일 storage root·image/hash·registration ID로 재시작한다. 이력을 새 저장소에 복사해
   복구 완료로 간주하지 않는다. `/runs/{id}`와 `/dashboard/v1/runs/{id}/execution`을 확인한다.
3. `/dashboard/v1/modules`, `/dashboard/v1/experiments/{id}`로 등록과 준비 상태를 조회한다.
   NOT_BUILT는 배포물, UNAVAILABLE/DOCKER_UNAVAILABLE은 엔진·이미지, INCOMPATIBLE은
   hash·engine·이전 복구 기록을 대조한다. budget 거절은 POST 503/WORKER_STORAGE_BUDGET이다.
   catalog readiness는 이미지/복구 상태이며 접수 시점의 디스크 용량 보장은 아니다.
4. 컨테이너 `recover-on-startup=true`는 모듈 문서의 확인 가능한 기록만 복구한다.
   종료 미확인·소유권 불일치·engine 변경은 [실패 주입 표](worker-fault-injection.md)와
   [복구 절차](worker-recovery.md)를 따른다. 공유 Docker prune/restart를 복구 수단으로 쓰지 않는다.
5. 실패 Run의 CHECK/REPORT를 먼저 보존한다. REPORT download 409는 원본 변조·크기·경로를,
   404는 누락을 확인하고 metadata를 임의 수정해 오류를 숨기지 않는다.

## 전환 parity

기존 Java 내장 화면은 제거했다. 대시보드가 있으면 기존 주소를 [주소 전환표](dashboard-deployment.md#기존-주소-전환과-되돌리기)대로 302 전환하고, API만 빌드한 경우 404다. 기존 API는 유지한다.

| 항목 | 대시보드 | 기존 API |
| --- | --- | --- |
| queue/IO/Excel 실행·조회 | schema form와 전용 Excel 입력, 현재 DB/IO 도구 | 기존 POST·조회 API 유지 |
| 이력·비교·export | 기존 v1 + 실행/check v2 | 기존 v1 그대로 |
| JFR/log/REPORT | 관리 다운로드, 구 서버 REPORT 참조 전용 | 다운로드 API 유지 |
| Career CHECK·worker 환경 | 실행/detail 지원 | 확장 API |
| browser 설정 | JSON export/import·origin 이전, v0.3.0까지 내보낸 JSON 가져오기 | 해당 없음 |
| 기존 화면 주소 | 302 전환, `legacy`를 뺀 query 유지 | 대시보드가 없으면 404 |

대용량 조회 검증은 `RunEvidenceTest`가 실제 H2에 metric 10,000개를 100개씩 저장하고
저장소를 재개방해 export한다. 출력 body를 쌓지 않는 counting sink로 10,002줄을 세며,
DB 조회가 100개 페이지 101회(마지막 빈 페이지 포함)인지 확인한다. 이는 보유 행 수
경계의 검증이며 전체 JVM heap의 상한 계측은 아니다.

## 최종 검증 기록 (2026-10-15)

- `./gradlew test`: 385건, 실패/건너뜀 0. 10,000행 export는 101회 조회(최대
  100행/페이지), 10,002줄, 1,227,434 byte를 응답 누적 없이 전송했다.
- `./scripts/career-lab.sh test`: 150건, 실패/건너뜀 0. 변경 없는 task의 성공 결과
  재사용을 포함한다. 이후 여섯 worker manifest를 현재 jar hash로 생성했다.
- `./gradlew test creatorContainerTest containerWorkerTest bootJar`: 당시 루트 384건,
  Creator 10건, 공통 컨테이너 17건 통과. 이후 추가한 export 검사 포함 최종 루트가 385건이다.
  Creator 열 변형은 저장소 재개방 후 REPORT 다운로드의 byte/hash도 확인했다.
- frontend `npm test`: 18건; typecheck/build 성공; `npm run test:serve`: 1건 통과.
- 실제 API + 전체 browser: 66건 통과, 별도 sequence 서버/저장 Run을 요구하는 선택적
  2건은 건너뜀. queue/IO/Excel은 실제 API로 실행했다. Career snapshot은 D5-1–D5-5
  컨테이너 결과를 재사용했으며, D6의 새 실제 컨테이너 실행은 Creator/공통 suite다.
- 데스크톱/375px 모바일 REPORT 캡처를 눈으로 확인했다. 테스트 API/Vite를 종료하고
  해당 임시 API 저장소를 제거했다. 소유 worker 컨테이너·Creator network·시험 이미지
  태그가 남지 않았다. 공유 Docker/다른 세션 자원을 변경하지 않았다.

처음 전체 browser 실행은 API 서버 미기동으로 실패해 중단했다. 전용 임시 API를
기동한 뒤 아래 명령으로 전체 재검증했다. serve test의 sandbox 포트 제한도 로컬
listen 권한으로 재실행해 통과했다. 테스트를 비활성화해 성공 처리하지 않았다.
아래 명령의 환경 변수 이름은 현재 기준이다(당시 `API_URL` 대신 이전 이름을 사용).

```bash
# 전용 API 18086, frontend 5196을 먼저 시작한다. frontend/에서 실행
DASHBOARD_URL=http://127.0.0.1:5196 \
API_URL=http://127.0.0.1:18086 \
MCP_CHECKS_EVIDENCE=../build/verification/d5-1 \
EQUITY_EVIDENCE=../build/verification/d5-2 \
MEMORY_EVIDENCE=../build/verification/d5-3 \
MDS_EVIDENCE=../build/verification/d5-4 \
CREATOR_EVIDENCE=../build/verification/d5-5 npm run test:e2e
```

snapshot 경로가 없으면 각 worker 문서의 container test를 먼저 실행한다. 원시 테스트
보고서는 `build/reports/tests/`, `career-lab/*/build/reports/tests/`,
`frontend/test-results/`에 생성되며 Git에는 위 재현 명령과 검증 결과·선택한 캡처를 보존한다.
