# Run 조회·비교와 증거

## 구현 단위

1. 저장된 두 Run의 조건/summary 비교, 종료 Run의 NDJSON export, artifact 참조 metadata API와 재시작 검증.
2. registry 목록/상세와 Run 목록/상세/비교 화면, Finding 템플릿, 브라우저 확인.

비교는 수치의 우열을 자동 판정하지 않는다. 실험 종류·상태·환경·Git·입력 checksum·측정 모드·파라미터 차이를 명시한다. 단위를 아는 summary만 단위를 표시하고 나머지는 unknown으로 둔다. 누락값은 0으로 바꾸지 않는다. 실행 중/실패 결과는 완전한 성능 결과가 아니다.

Export는 종료 Run만 허용하며 Run, 원본 metric, artifact 참조를 NDJSON으로 순차 전송한다. DB 조회는 page limit 이내로 하고 마지막 end 레코드로 완전한 다운로드인지 판별한다. 서버에 export 파일을 만들지 않는다.

Artifact는 사용자 제공 또는 내장 수집기가 생성한 파일의 참조 metadata다. 등록 API 자체는 경로를 열거나 파일 내용을 검증/다운로드하지 않는다. 선택적 JFR·Run lifecycle 로그 수집기는 파일을 만들고 실제 크기·SHA-256을 계산해 등록한다. 수집 범위와 파일 위치는 [관측 가이드](observability.md)를 참조한다. 참조는 Run당 최대 100개이고 ID별 불변·재등록은 동일 내용에 한해 허용한다.

## API

- `GET /runs/compare?baseline=<runId>&candidate=<runId>`: 두 Run 전체, 조건별 different 표시, 단위가 포함된 summary 값과 cautions. 누락값은 null이다.
- `GET /runs/{id}/export`: 종료 Run의 NDJSON attachment. 실행 중이면 409, 없는 Run은 404다. 마지막 `type=end`의 metricCount/artifactCount를 확인한다. 전송 중 저장소/연결 오류가 나면 end가 없는 불완전 파일은 증거로 사용하지 않는다.
- `GET /runs/{id}/artifacts`: 최대 100개의 사용자 제공/내장 수집기 참조.
- `GET /runs/{id}/artifacts/{artifactId}/download`: 종료 Run의 관리 경로 파일을 크기/SHA-256 검증 후 attachment로 전송한다. 아래 다운로드 계약을 참고한다.
- `PUT /runs/{id}/artifacts/{artifactId}`: 8 KiB 이하 JSON `{ "relativePath": "profiles/run.jfr", "mediaType": "application/octet-stream", "byteSize": 123, "sha256": "64자리 소문자 hex" }`. ID는 전역에서 유일한 영숫자/underscore/hyphen 1–100자다. 상대 경로만 허용하고 traversal은 거부한다. 같은 내용 재등록은 idempotent, 충돌/잘못된 metadata는 400, 크기 초과는 413이다.

사용자가 API로 등록한 참조의 checksum은 서버 검증을 의미하지 않는다. 원본 파일은 별도로 보관한다. export는 시작 시 읽은 참조 목록을 포함하고 이후 등록은 다음 export에 반영된다. 새로운 DB 스키마 변경 없이 기존 v1 lab_artifact 테이블을 사용한다.

UI는 `GET /runs/ui/options`에서 서버 page limit 이하의 표시 크기를 받는다.

## 화면과 Finding

서버 시작 후 `/runs/ui`를 연다. 홈의 Browse and compare 링크에서도 접근한다.

- 왼쪽 registry에서 실험을 선택하면 파라미터 schema와 해당 실험의 저장된 Run을 조회한다.
- 목록은 최대 20개씩 (`lab.storage.page-limit`가 더 작으면 해당 상한) Older/Newest로 이동하며 상세는 상태·실패 이유·가설·환경·Git·파라미터·summary·artifact·metric 페이지를 제공한다.
- 상세의 Use as baseline/candidate로 대상을 정한 뒤 Compare runs를 누른다. 두 선택 ID만 sessionStorage에 보관하며 실행 데이터는 저장소에서 조회한다.
- `/runs/ui?run=<id>` 또는 `/runs/ui?baseline=<id>&candidate=<id>`를 저장해 다시 연다. 브라우저 뒤로 가기도 지원한다.
- 좁은 화면의 비교 표는 표 안에서 가로 스크롤한다. 로딩/빈 결과/오류를 명시하고 저장된 문자열은 HTML로 해석하지 않는다.
- 실행 중에는 Refresh run으로 갱신한다. 자동 polling은 하지 않는다.
- [Finding 템플릿](findings/TEMPLATE.md)에 증거와 해석을 기록한다. 결과 자동 판정이나 Finding 자동 저장은 제공하지 않는다.

기존 화면의 어두운 색상 계열을 유지하고 system font, 명시적 표 헤더, 44px 조작 영역과 키보드 focus를 적용했다. summary 단위는 현재 알려진 이름만 명시한 최소 catalog이며 전체 metric schema 확장은 다음 관측 단계에서 진행한다.

## JFR·Run 로그 다운로드

`GET /runs/{id}/artifacts/{artifactId}/download`는 해당 Run에 등록된 참조 중 `jfr/jfr_<UUID>.jfr` 또는 `logs/log_<UUID>.ndjson` 형태만 지원한다. ID와 파일명이 일치해야 하며 미디어 타입도 각각 application/octet-stream, application/x-ndjson이어야 한다. UUID는 소문자 hex 표기다. 이는 관리 경로 규칙이며 등록자를 인증하거나 내장 수집기가 생성했다는 출처 증명은 아니다. 일반 사용자 제공 파일 참조는 목록/export에 남고 이 API로 읽지 않는다.

```bash
curl --fail --output run-log.ndjson \
  'http://localhost:8080/runs/<runId>/artifacts/<artifactId>/download'
```

| 설정 | 기본값 | 허용 범위 |
| --- | --- | --- |
| lab.artifacts.download.max-bytes | 268435456 (256 MiB) | 1–268435456 bytes |
| lab.artifacts.download.max-concurrent | 2 | 1–8 |

전체 파일을 메모리에 올리지 않는다. 8 KiB 버퍼로 크기와 SHA-256을 검증하면서 JVM 임시 디렉터리에 비공개 사본을 만든 뒤, 검증이 끝난 그 사본을 전송한다. 원본이 이후 바뀌어도 전송 내용은 검증된 사본과 일치한다. 임시 디스크 사용량은 동시 슬롯 수 × max-bytes 이내이며, 슬롯은 검증과 전송 전체 동안 유지한다. 연결 오류·검증 실패·정상 종료에서 사본을 삭제하고 슬롯을 반환한다. 프로세스 강제 종료 시 남은 `lab-artifact-*.snapshot` 파일은 자동 정리하지 않으며 프로세스가 종료된 뒤 운영자가 정리한다. 기존 수집 파일은 수정하거나 삭제하지 않는다.

storage root는 신뢰할 수 있는 서버 설정이다. 그 아래 jfr/logs 디렉터리와 파일은 SecureDirectoryStream의 상대 경로·NOFOLLOW 접근을 사용한다. 심볼릭 링크와 일반 파일이 아닌 항목은 거부한다. 해당 기능을 지원하지 않는 파일시스템에서는 안전성이 낮은 경로 접근으로 대체하지 않고 503을 반환한다. 로컬 파일 소유자의 악의적 변경에 대한 접근 제어를 대신하지 않는다.

| 응답 | 의미 |
| --- | --- |
| 200 | 검증된 attachment; Content-Length, X-Artifact-SHA256, no-store, nosniff 포함 |
| 404 | Run/참조/파일이 없음; 다른 Run의 참조는 조회되지 않음 |
| 409 | 실행 중, 미지원 관리 경로/타입, symlink, 크기/checksum 불일치 |
| 413 | 등록된 크기가 다운로드 상한 초과 |
| 429 | 동시 다운로드 슬롯 소진; 큐에 무제한 적재하지 않음 |
| 503 | SecureDirectoryStream 미지원 |

임시 디스크 부족 등 그 밖의 I/O 오류는 서버 오류로 전파한다. 전송 중 연결이 끊어지면 완료된 응답으로 간주하지 말고 Content-Length와 SHA-256을 확인한다. Range/부분 다운로드, 자동 파일 보관·삭제와 UI 다운로드 버튼은 이번 API에 포함하지 않는다.

검증: ArtifactDownloadTest는 재개방 뒤 정확한 바이트·헤더, 누락/다른 Run/실행 중/일반 참조/상한 거부, 파일 변조와 링크, 원본 변경 후 사본 일치, 슬롯 상한 및 연결 실패 뒤 슬롯 반환을 확인한다. LabCliIntegrationTest는 실제 서버가 생성한 세 실험의 JFR/로그를 내려받아 저장된 SHA-256과 비교한다.
