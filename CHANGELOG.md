# Changelog

저장소 전체 릴리스는 `vMAJOR.MINOR.PATCH` annotated Git tag로 기록한다.
태그는 다시 지정하지 않으며, 각 릴리스의 변경 내용과 검증 범위는 별도 기록에 남긴다.
컴포넌트의 Gradle/npm 버전은 저장소 릴리스 태그와 별도로 관리한다.

태그를 push하면 `.github/workflows/release.yml`이 태그 소스로 frontend·루트·브라우저 테스트를 다시 실행하고, 대시보드 내장 jar와 frontend 묶음, `SHA256SUMS`를 첨부한 **초안** GitHub Release를 만든다. 본문은 `docs/releases/<tag>.md`를 쓴다. 초안 공개와 운영 전환은 기존처럼 수동이며, `docs/releases/<tag>-deployment.md`에 별도로 기록한다. Career worker jar·Docker 이미지·Run 데이터는 첨부하지 않는다.

## Unreleased

- 기본 `bootJar`가 React 대시보드를 내장하고, 기존 화면 주소는 기본으로 `/app`에 전환한다(`lab.dashboard.redirect-enabled=true`). 패키징에 Node가 필요하며 `-PskipFrontend`로 제외할 수 있다.
- 기존 Java 내장 화면은 이번 릴리스까지 `?legacy=true`로 열 수 있고 제거 안내를 표시한다. **다음 릴리스에서 제거한다.** `bootDashboardJar`도 이번 릴리스까지만 별칭으로 남는다.
- 반복 측정·기준선 회귀 판정·파라미터 스윕, 캡처 보관 정리, Finding 초안, dead-letter 화면. 저장 schema v4(시작 시 자동 백업 후 이행).
- 반복 측정 중에는 다른 Run 접수를 409로 거절하고, 그래도 겹친 Run은 묶음 상세에 표시한다(`lab.series.exclusive`).
- Excel 측정 계약 1.1.0: 저장 처리량(`throughput.insertedRowsPerSec`)과 20ms 표본 최대 heap(`heap.sampledPeakBytes`)을 measure로 추가. 이전 Run은 실행 당시 계약을 그대로 쓴다.
- Excel 시나리오 `forkedHeapMiB`: 실행마다 `-Xmx`를 건 자식 JVM에서 처리해 heap을 그 실행만의 값으로 기록한다.
- Run마다 호스트 부하(`host.load1m.*`, `host.cpuPct.end`)를 기록하고, 변동 계수가 10%를 넘는 묶음은 상세와 Finding 초안에 경고한다.
- 증거 묶음 zip(`/evidence-bundles`): Run export·검증된 캡처·묶음 통계·비교·Finding 초안·manifest·SHA256SUMS를 한 파일로 내려받는다.
- 기준선 파일(`GET /run-series/{id}/baseline-file`, `lab baseline ID --export`)과 파일 기준 회귀 판정(`--check-baseline-file`), 야간 Performance regression 워크플로. `heap` measure는 낮을수록 좋음으로 판정한다.
- Finding 문서가 인용한 Run을 색인해 Run 상세에 표시하고 보관 정리에서 제외한다(`/findings`, `lab.findings.directory`).
- 반복 묶음·스윕 중지(`POST .../cancel`, 대시보드 중지 버튼). 실행 중인 Run은 끝까지 돌고 다음 반복부터 멈춘다.
- 대시보드 실행 폼이 Excel 워크북을 받아 조건 지정 단일 실행·반복 측정·스윕을 제출한다.
- 반복 묶음이 CHECK별 통과율을 집계하고, 반복마다 판정이 달라진 CHECK를 불안정으로 표시한다.
- 실행 기록을 서버에서 시나리오 ID 시작 문자열과 상태로 거른다. 페이지를 넘겨도 조건이 유지된다.

## v0.1.0 — 2026-10-16

첫 통합 소스 릴리스. 기존 실험 3개·Career 13개 시나리오, 독립 대시보드,
컨테이너 실행과 증거 보존, 로컬 배포·상시 실행·백업/복원을 포함한다.

[릴리스 기록](docs/releases/v0.1.0.md)
