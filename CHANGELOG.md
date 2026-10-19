# Changelog

저장소 전체 릴리스는 `vMAJOR.MINOR.PATCH` annotated Git tag로 기록한다.
태그는 다시 지정하지 않으며, 각 릴리스의 변경 내용과 검증 범위는 별도 기록에 남긴다.
컴포넌트의 Gradle/npm 버전은 저장소 릴리스 태그와 별도로 관리한다.

태그를 push하면 `.github/workflows/release.yml`이 태그 소스로 frontend·루트·브라우저 테스트를 다시 실행하고, 대시보드 내장 jar와 frontend 묶음, `SHA256SUMS`를 첨부한 **초안** GitHub Release를 만든다. 본문은 `docs/releases/<tag>.md`를 쓴다. 초안 공개와 운영 전환은 기존처럼 수동이며, `docs/releases/<tag>-deployment.md`에 별도로 기록한다. Career worker jar·Docker 이미지·Run 데이터는 첨부하지 않는다.

## Unreleased

- 기본 `bootJar`가 React 대시보드를 내장하고, 기존 화면 주소는 기본으로 `/app`에 전환한다(`lab.dashboard.redirect-enabled=true`). 패키징에 Node가 필요하며 `-PskipFrontend`로 제외할 수 있다.
- 기존 Java 내장 화면은 이번 릴리스까지 `?legacy=true`로 열 수 있고 제거 안내를 표시한다. **다음 릴리스에서 제거한다.** `bootDashboardJar`도 이번 릴리스까지만 별칭으로 남는다.
- 반복 측정·기준선 회귀 판정·파라미터 스윕, 캡처 보관 정리, Finding 초안, dead-letter 화면. 저장 schema v4(시작 시 자동 백업 후 이행).

## v0.1.0 — 2026-10-16

첫 통합 소스 릴리스. 기존 실험 3개·Career 13개 시나리오, 독립 대시보드,
컨테이너 실행과 증거 보존, 로컬 배포·상시 실행·백업/복원을 포함한다.

[릴리스 기록](docs/releases/v0.1.0.md)
