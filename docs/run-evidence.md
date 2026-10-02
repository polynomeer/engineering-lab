# Run 조회·비교와 증거

## 구현 단위

1. 저장된 두 Run의 조건/summary 비교, 종료 Run의 NDJSON export, artifact 참조 metadata API와 재시작 검증.
2. registry 목록/상세와 Run 목록/상세/비교 화면, Finding 템플릿, 브라우저 확인.

비교는 수치의 우열을 자동 판정하지 않는다. 실험 종류·상태·환경·Git·입력 checksum·측정 모드·파라미터 차이를 명시한다. 단위를 아는 summary만 단위를 표시하고 나머지는 unknown으로 둔다. 누락값은 0으로 바꾸지 않는다. 실행 중/실패 결과는 완전한 성능 결과가 아니다.

Export는 종료 Run만 허용하며 Run, 원본 metric, artifact 참조를 NDJSON으로 순차 전송한다. DB 조회는 page limit 이내로 하고 마지막 end 레코드로 완전한 다운로드인지 판별한다. 서버에 export 파일을 만들지 않는다.

Artifact는 사용자 관리 파일의 참조 metadata다. 경로·미디어 타입·크기·SHA-256을 저장하되 서버에서 경로를 열거나 파일 내용을 검증/다운로드하지 않는다. 파일 수집·JFR 생성은 다음 관측 단계다. 참조는 Run당 최대 100개이고 ID별 불변·재등록은 동일 내용에 한해 허용한다.

## API

- `GET /runs/compare?baseline=<runId>&candidate=<runId>`: 두 Run 전체, 조건별 different 표시, 단위가 포함된 summary 값과 cautions. 누락값은 null이다.
- `GET /runs/{id}/export`: 종료 Run의 NDJSON attachment. 실행 중이면 409, 없는 Run은 404다. 마지막 `type=end`의 metricCount/artifactCount를 확인한다. 전송 중 저장소/연결 오류가 나면 end가 없는 불완전 파일은 증거로 사용하지 않는다.
- `GET /runs/{id}/artifacts`: 최대 100개의 사용자 제공 참조.
- `PUT /runs/{id}/artifacts/{artifactId}`: 8 KiB 이하 JSON `{ "relativePath": "profiles/run.jfr", "mediaType": "application/octet-stream", "byteSize": 123, "sha256": "64자리 소문자 hex" }`. ID는 전역에서 유일한 영숫자/underscore/hyphen 1–100자다. 상대 경로만 허용하고 traversal은 거부한다. 같은 내용 재등록은 idempotent, 충돌/잘못된 metadata는 400, 크기 초과는 413이다.

등록한 참조의 checksum은 사용자가 계산한 값이며 서버 검증을 의미하지 않는다. 원본 파일은 별도로 보관한다. export는 시작 시 읽은 참조 목록을 포함하고 이후 등록은 다음 export에 반영된다. 새로운 DB 스키마 변경 없이 기존 v1 lab_artifact 테이블을 사용한다.

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
