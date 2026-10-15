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
REPORT 링크를 표시한다. 구 서버에서는 참조 전용으로 유지한다. 기존 `/runs/ui`는
동일 서버의 options 값 `workerReportDownload=1`을 사용한다. 기존 API 경로·참조
스키마와 JFR/log 다운로드는 유지한다.
