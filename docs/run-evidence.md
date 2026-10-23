# Run 조회·비교와 증거

## 구현 단위

1. 저장된 두 Run의 조건/summary 비교, 종료 Run의 NDJSON export, artifact 참조 metadata API와 재시작 검증.
2. registry 목록/상세와 Run 목록/상세/비교 화면, Finding 템플릿, 브라우저 확인.

비교는 수치의 우열을 자동 판정하지 않는다. 실험 종류·상태·환경·Git·입력 checksum·측정 모드·파라미터 차이를 명시한다. 단위를 아는 summary만 단위를 표시하고 나머지는 unknown으로 둔다. 누락값은 0으로 바꾸지 않는다. 실행 중/실패 결과는 완전한 성능 결과가 아니다.

Export는 종료 Run만 허용하며 Run, 원본 metric, artifact 참조를 NDJSON으로 순차 전송한다. DB 조회는 page limit 이내로 하고 마지막 end 레코드로 완전한 다운로드인지 판별한다. dead-letter 행 본문과 원본 입력·artifact bytes는 포함하지 않으므로 별도 보관한다. 서버에 export 파일을 만들지 않는다.

Artifact는 사용자 제공 또는 내장 수집기가 생성한 파일의 참조 metadata다. 등록 API 자체는 경로를 열거나 파일 내용을 검증/다운로드하지 않는다. 선택적 JFR·Run lifecycle 로그 수집기는 파일을 만들고 실제 크기·SHA-256을 계산해 등록한다. 수집 범위와 파일 위치는 [관측 가이드](observability.md)를 참조한다. 참조는 Run당 최대 100개이고 ID별 불변·재등록은 동일 내용에 한해 허용한다.

## API

- `GET /runs/compare?baseline=<runId>&candidate=<runId>`: 두 Run 전체, 조건별 different 표시, 단위가 포함된 summary 값과 cautions. 누락값은 null이다.
- `GET /runs/{id}/export`: 종료 Run의 NDJSON attachment. 실행 중이면 409, 없는 Run은 404다. 마지막 `type=end`의 metricCount/artifactCount를 확인한다. 전송 중 저장소/연결 오류가 나면 end가 없는 불완전 파일은 증거로 사용하지 않는다.
- `GET /runs/{id}/artifacts`: 최대 100개의 사용자 제공/내장 수집기 참조.
- `GET /runs/{id}/artifacts/{artifactId}/download`: 종료 Run의 관리 경로 파일을 크기/SHA-256 검증 후 attachment로 전송한다. 아래 다운로드 계약을 참고한다.
- `PUT /runs/{id}/artifacts/{artifactId}`: 8 KiB 이하 JSON `{ "relativePath": "profiles/run.jfr", "mediaType": "application/octet-stream", "byteSize": 123, "sha256": "64자리 소문자 hex" }`. ID는 전역에서 유일한 영숫자/underscore/hyphen 1–100자다. 상대 경로만 허용하고 traversal은 거부한다. 같은 내용 재등록은 idempotent, 충돌/잘못된 metadata는 400, 크기 초과는 413이다.

사용자가 API로 등록한 참조의 checksum은 서버 검증을 의미하지 않는다. 원본 파일은 별도로 보관한다. export는 시작 시 읽은 참조 목록을 포함하고 이후 등록은 다음 export에 반영된다. 새로운 DB 스키마 변경 없이 기존 v1 lab_artifact 테이블을 사용한다.

UI는 `GET /runs/ui/options`에서 서버 page limit 이하의 표시 크기와 artifactDownloadMaxBytes를 받는다.

## 화면과 Finding

대시보드의 `/app/runs`에서 Run을 조회한다. 기존 `/runs/ui` 주소는 대시보드로 전환되며 화면은 제거했다.

- 실행 기록은 실험·시나리오 ID 시작·상태 필터를 서버에서 적용한다. 목록은 options의 page 크기(기본 20, 서버 상한 이하)로 이전/최신 실행을 이동한다.
- 상세 `/app/runs/<id>`는 상태·실패 이유·가설·환경·Git·파라미터·summary·artifact·metric 페이지를 제공한다. 실행 중인 Run은 자동으로 다시 조회한다.
- 상세의 **비교 기준으로 선택** 후 비교 Run ID를 입력해 `/app/compare?baseline=<id>&candidate=<id>`를 연다. 주소를 저장해 다시 열 수 있다.
- 비교 화면은 비교 요약 CSV/JSON, Finding 초안(.md), 증거 묶음(.zip)을 내려받는다. 상세는 NDJSON export와 증거 묶음을 제공한다.
- 상세 첨부 증거 표의 다운로드 링크로 관리 파일을 받는다. 새 탭에서 요청하므로 원래 상세 화면을 유지하고 오류 응답은 새 탭에 표시한다. 서버 크기 상한 초과·참조 전용 항목에는 링크 대신 사유를 표시한다. 파일 존재/checksum의 최종 판단은 클릭 시 서버가 수행한다.
- [Finding 템플릿](findings/TEMPLATE.md)에 증거와 해석을 기록한다. 결과 자동 판정이나 Finding 자동 저장은 제공하지 않는다.

단위·의미는 `/metrics/schema`의 catalog v2에서 확인한다. 알려지지 않은 metric 이름의 단위는 추정하지 않는다.

<a id="jfr-run-download"></a>

## JFR·Run 로그 다운로드

`GET /runs/{id}/artifacts/{artifactId}/download`는 해당 Run에 등록된 참조 중 `jfr/jfr_<UUID>.jfr`, `logs/log_<UUID>.ndjson`, `worker-reports/worker_<UUID>.bin` 형태를 지원한다. ID와 파일명이 일치해야 하며 미디어 타입도 각각 application/octet-stream, application/x-ndjson, application/octet-stream이어야 한다. UUID는 소문자 hex 표기다. 이는 관리 경로 규칙이며 등록자를 인증하거나 내장 수집기가 생성했다는 출처 증명은 아니다. 일반 사용자 제공 파일 참조는 목록/export에 남고 이 API로 읽지 않는다.

```bash
curl --fail --output run-log.ndjson \
  'http://localhost:8080/runs/<runId>/artifacts/<artifactId>/download'
```

| 설정 | 기본값 | 허용 범위 |
| --- | --- | --- |
| lab.artifacts.download.max-bytes | 268435456 (256 MiB) | 1–268435456 bytes |
| lab.artifacts.download.max-concurrent | 2 | 1–8 |

전체 파일을 메모리에 올리지 않는다. 8 KiB 버퍼로 크기와 SHA-256을 검증하면서 JVM 임시 디렉터리에 비공개 사본을 만든 뒤, 검증이 끝난 그 사본을 전송한다. 원본이 이후 바뀌어도 전송 내용은 검증된 사본과 일치한다. 임시 디스크 사용량은 동시 슬롯 수 × max-bytes 이내이며, 슬롯은 검증과 전송 전체 동안 유지한다. 연결 오류·검증 실패·정상 종료에서 사본을 삭제하고 슬롯을 반환한다. 프로세스 강제 종료 시 남은 `lab-artifact-*.snapshot` 파일은 자동 정리하지 않으며 프로세스가 종료된 뒤 운영자가 정리한다. 기존 수집 파일은 수정하거나 삭제하지 않는다.

storage root는 신뢰할 수 있는 서버 설정이다. 그 아래 jfr/logs/worker-reports 디렉터리와 파일은 SecureDirectoryStream의 상대 경로·NOFOLLOW 접근을 사용한다. 심볼릭 링크와 일반 파일이 아닌 항목은 거부한다. 해당 기능을 지원하지 않는 파일시스템에서는 안전성이 낮은 경로 접근으로 대체하지 않고 503을 반환한다. 로컬 파일 소유자의 악의적 변경에 대한 접근 제어를 대신하지 않는다.

| 응답 | 의미 |
| --- | --- |
| 200 | 검증된 attachment; Content-Length, X-Artifact-SHA256, no-store, nosniff 포함 |
| 404 | Run/참조/파일이 없음; 다른 Run의 참조는 조회되지 않음 |
| 409 | 실행 중, 미지원 관리 경로/타입, symlink, 크기/checksum 불일치 |
| 413 | 등록된 크기가 다운로드 상한 초과 |
| 429 | 동시 다운로드 슬롯 소진; 큐에 무제한 적재하지 않음 |
| 503 | SecureDirectoryStream 미지원 |

임시 디스크 부족 등 그 밖의 I/O 오류는 서버 오류로 전파한다. 전송 중 연결이 끊어지면 완료된 응답으로 간주하지 말고 Content-Length와 SHA-256을 확인한다. Range/부분 다운로드와 자동 파일 보관·삭제는 이번 API에 포함하지 않는다.

검증: ArtifactDownloadTest는 재개방 뒤 정확한 바이트·헤더, 누락/다른 Run/실행 중/일반 참조/상한 거부, 파일 변조와 링크, 원본 변경 후 사본 일치, 슬롯 상한 및 연결 실패 뒤 슬롯 반환을 확인한다. LabCliIntegrationTest는 실제 서버가 생성한 세 실험의 JFR/로그를 내려받아 저장된 SHA-256과 비교한다.

## 실행 정보·검증 결과 확장 API

앱에 등록된 신규 실험은 Run 생성과 execution definition을 하나의 트랜잭션으로 기록한다. module ID는 서버 catalog에서 가져온다. 초기 PREPARING, 실험 호출 직전 MEASURING, 관측 종료 시작 시 FINALIZING을 기록한다. 수집·writer drain·artifact 등록이 끝나고 실행이 성공한 경우에만 evidenceComplete를 true로 확정한다. 실패·중단은 보수적으로 false를 유지한다. 기존 Run과 catalog를 전달하지 않은 직접 runner 호출은 execution 미기록일 수 있다.

| API | 응답과 제약 |
| --- | --- |
| `GET /dashboard/v1/runs/{id}/execution` | `{runId, available, execution}`. 실행 중 phase 조회 가능. 기록이 없으면 available=false/execution=null, 없는 Run은 404 |
| `GET /dashboard/v1/runs/{id}/checks?after=-1&limit=100` | `{runId, available, checks, nextAfter}`. 종료 Run만 허용(실행 중 409), limit은 1–min(page-limit,100), after는 -1 이상. 잘못된 범위 400 |
| `GET /dashboard/v1/runs/{id}/export` | NDJSON schemaVersion=2. run → execution view → metric → check → artifact-reference → end 순서. end에 metricCount/checkCount/artifactCount |

checks의 available은 execution 기록 여부이며 check가 실제 수행됐다는 뜻은 아니다. 빈 목록을 검증 통과로 해석하지 않는다. worker는 handshake 전 null이고, 실패·중단 Run에서도 이미 보존된 부분 증거를 조회할 수 있다. 이 단계에서는 실제 worker나 check 생성 실험을 등록하지 않는다.

신규 export도 페이지 단위로 전송하며 파일을 서버나 메모리에 전체 보관하지 않는다. `Content-Disposition: attachment; filename=run-evidence-v2.ndjson`과 no-store를 사용한다. end가 없으면 불완전한 다운로드다. dead-letter 본문·원본 입력·artifact bytes는 별도 보관한다. 기존 `/runs/{id}/export`는 계속 schemaVersion=1이며 신규 execution/check를 포함하지 않는다. 기존 CLI/화면의 다운로드 형식을 변경하지 않았다.

```bash
curl --fail 'http://localhost:8080/dashboard/v1/runs/<runId>/execution'
curl --fail 'http://localhost:8080/dashboard/v1/runs/<runId>/checks?after=-1&limit=100'
curl --fail --output run-v2.ndjson 'http://localhost:8080/dashboard/v1/runs/<runId>/export'
```

`/runs/compare`는 기존 응답 필드 안에 `execution.*` 조건과 cautions를 추가한다. IN_PROCESS는 서버 환경/Git, LOCAL_JVM은 실제 worker 환경/Git/artifact hash를 사용한다. 미확인 worker 환경은 null이며 서버 JVM으로 대체하지 않는다. 실행 종류·모듈·protocol·측정 범위·완료 상태가 다르거나 worker Git이 불명/dirty인 경우 주의사항을 포함한다. 두 Run 모두 execution이 없으면 기존 비교 응답을 유지한다.

기존 비교 API 사용자는 공통 조건·주의사항 형식으로 확장 정보를 받는다.

## 지원 기능과 catalog 확장

| API | 응답 |
| --- | --- |
| `GET /dashboard/v1/capabilities` | schemaVersion=1, executionKinds, workerProtocolVersions, features, limits |
| `GET /dashboard/v1/modules` | 등록 모듈의 `{module, readiness, reason, experimentIds}` 목록 |
| `GET /dashboard/v1/experiments/{id}` | experimentId, moduleId, descriptorUrl, readiness, executionKind, variants, presentation, capabilities. 없는 ID는 404 |

현재 executionKinds는 IN_PROCESS이고 workerProtocolVersions는 빈 목록이다. features의 executionEvidence/checksRead/extendedExport/executionComparison은 true, localJvm/cancellation은 아직 구현하지 않아 false다. jvmObservation/trace/jfr/runLogs는 현재 서버 설정을 반영한다. limits는 checkPageSize=min(설정 page-limit,100), maxChecksPerRun=100000, maxCheckBytes=65536, maxActiveRuns=설정값이다.

모듈·실험 목록은 기존 catalog에서 가져온다. 현재 서버는 시작 시 runtime binding을 검증한 in-process 모듈만 등록하므로 readiness=READY, reason=null이다. 이는 미등록 Career 모듈이나 Docker/worker의 준비 완료를 의미하지 않는다. 향후 외부 실행기는 별도 사전 점검과 준비 불가 사유를 연결해야 한다.

실험 확장은 기존 descriptor를 수정하지 않고 descriptorUrl로 참조한다. 현재 별도 variant 정의가 없어 variants는 빈 목록이며 measurementModes를 variant로 추정하지 않는다. presentation은 SUMMARY/METRICS/EXECUTION이다. capabilities는 공통 features에 fileUpload(XLSX_UPLOAD 여부)와 checksProduced=false를 더한다. checksRead=true는 저장된 check 조회 지원이며 현재 실험이 check를 생성한다는 뜻은 아니다.

신규 화면은 schemaVersion=1만 인식한다. 404·지원하지 않는 버전·잘못된 capability 구조는 기존 API 모드로 전환하고, 기능은 명시적인 boolean true만 활성화 대상으로 해석한다. 503 등 서버 오류는 화면에 표시한다. 기존 `/modules`와 `/experiments` 기반 실행·조회·내보내기 흐름은 유지한다.


## 외부 worker 저장 어댑터 검증 범위

`LocalJvmExecutor`는 서버가 등록한 manifest·파라미터 변환기·허용 metric key 집합으로만 실행한다. HELLO 환경/Git/hash는 execution.worker에 저장하고 기존 Run metadata의 서버 환경은 유지한다. CHECK는 전체 이벤트 순번과 별개인 0부터 증가하는 cursor로 저장한다. 메트릭은 기존 bounded writer와 retry/drain 경로를 사용한다.

정상 RESULT뿐 아니라 EOF·exit 0·저장 flush·프로세스 정리를 확인한 뒤 중앙 runner가 증거 완료를 기록한다. 실패한 Run의 확인된 증거는 보존하며, 프로세스 오류에 포함된 작업 폴더 이름은 summary의 worker.workspace로 남긴다. 절대 서버 경로는 이 값에 넣지 않는다. ARTIFACT는 아래 보고서 importer가 연결된 경우에만 REPORT를 검증·보관한다. importer 미설정이나 미지원 종류는 명시적으로 실패시킨다. 저장소가 phase의 인접 전이를 요구하므로 측정 전 실패 worker는 PREPARING에서 실패 RESULT를 보내야 한다.

이 경로는 실제 Java 21 테스트 worker와 H2 재개방으로 검증했으며, 운영 실험 catalog에는 아직 등록하지 않는다. 실제 Career 실행·Docker 준비/정리를 연결한 뒤 capability를 활성화한다.


## Worker 보고서 검증·보관

`WorkerArtifactImporter`는 worker의 REPORT 파일을 검증한 뒤 중앙 저장 루트의 `worker-reports/worker_<UUID>.bin`으로 복사하고 같은 Run에 artifact 참조를 등록한다. 원본 이름을 보관 경로에 사용하지 않는다. 내용 형식을 추측하지 않으며 미디어 타입은 `application/octet-stream`이다. JSON 파싱이나 보고서 내용의 정합성 검증을 대신하지 않는다.

서버가 관리하는 storageRoot와 workspace 부모 디렉터리를 사용한다. 경로 탈출·절대 경로·빈 경로 요소·심볼릭 링크·일반 파일이 아닌 항목은 거부한다. `SecureDirectoryStream`으로 workspace와 하위 디렉터리 핸들을 고정하고 마지막 파일도 NOFOLLOW로 연다. 이 기능이 없는 파일시스템에서는 실패하며 불안전한 경로 탐색으로 대체하지 않는다. 저장 루트는 worker 출력 디렉터리와 별개로 관리해야 한다.

8 KiB 버퍼로 복사하면서 실제 크기와 SHA-256이 선언과 일치하는지 확인한다. 검증한 임시 사본을 같은 디렉터리 안에서 원자적으로 이동한 후 DB 참조를 등록한다. 이후 원본이 변경돼도 보관한 사본은 달라지지 않는다. 검증이나 등록이 실패하면 이번 사본을 삭제하고 Run은 실패한다. 이전에 등록한 부분 증거는 유지한다. 파일 이동과 DB 등록은 하나의 트랜잭션이 아니므로 프로세스 강제 종료 시 미등록 파일이 남을 수 있다. D6 접수 예산은 보존 workspace/report 합계를 확인한다. 미등록 잔여 파일 자동 삭제와 파일시스템 hard quota는 제공하지 않는다.

| 실행별 제한 | 기본값 / 최대값 |
| --- | ---: |
| 파일 수 | 32개 |
| 파일당 크기 | 16 MiB |
| 합계 크기 | 64 MiB |

`WorkerArtifactImporter.Limits`로 더 작은 값을 주입할 수 있으며, 같은 Run의 중복 원본 경로와 실패 이후 재사용은 거부한다. 아직 운영 Spring 설정에는 연결하지 않았다. worker artifact는 REPORT만 지원하며 JFR/LOG 수집 capability는 false다. D6에서 REPORT 다운로드 경로를 통합했다.

보고서 참조는 기존 artifact 목록/export에 보존된다. 관리 다운로드 API와 신/구 화면에서 중앙 사본을 다운로드하며, 서버 보관 파일은 참조의 relativePath로 확인할 수 있다.

검증: `WorkerArtifactImporterTest`는 바이트 일치·원본 변경·경로/링크/종류 거부·해시/크기/개수/합계 상한·중복·등록 실패 정리를 확인한다. `LocalJvmExecutorTest`는 실제 Java 21 worker 보고서를 H2 참조와 함께 보존하고 재개방하며, 잘못된 해시가 성공이나 완료 증거로 처리되지 않음을 검증한다.

D6 관리 REPORT 다운로드와 관측 capability·접수 예산의 현재 계약은 [통합 운영 가이드](dashboard-operations.md)를 따른다. 위 capability 도입 시점 설명 이후 LOCAL_JVM/CONTAINER 등록은 활성화한 worker를 반영하며 workerReportDownload=true를 제공한다.

## 공통 검증 성질과 측정 계약 (P4)

[측정 계약과 비교 조건](measurement-contract.md)에서 선택 등록 bean, 실행 시점 snapshot, 신규 읽기 API,
단위·환경·구간 불일치 및 과거 Run의 UNKNOWN 처리를 설명한다. 기존 실행/내보내기 계약은 유지한다.
