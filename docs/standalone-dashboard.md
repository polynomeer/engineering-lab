# 독립 대시보드 사용 가이드

2026-10-05 기준 D1 구현입니다. 기존 API 서버와 별도로 실행하며 저장된 Run의 증거를 조회합니다.
설치·명령·검증은 [frontend 안내](../frontend/README.md)를 참고하세요.

## 시작과 조회

1. 루트에서 `./gradlew bootRun`으로 기존 API를 실행합니다.
2. Node 24.19.0 환경에서 `frontend/`로 이동해 `npx --yes npm@11.6.0 ci`, `npm run dev`를 실행합니다.
3. `http://127.0.0.1:5173/app/runs`를 엽니다.
4. **기존 실험 실행**으로 큐·IO·Excel Run을 만든 뒤 새 대시보드의 **새로고침**을 선택합니다.
5. 왼쪽 catalog에서 실험을 선택하거나 목록의 시나리오명을 눌러 상세로 이동합니다.

목록에는 서버가 저장한 실행만 표시합니다. Career 모듈은 worker 연결 전이므로 실행 가능한 catalog에 포함하지 않습니다.
현재 서버는 대시보드 확장 capabilities API가 없어 **기존 API 호환 모드**로 표시되는 것이 정상입니다.

## 상세와 비교

상세는 상태·가설·원본 summary·파라미터·JVM/Git·페이지별 관측·artifact를 보여줍니다.
**비교 기준으로 선택**을 누른 뒤 비교할 Run ID를 입력하면 조건과 측정값을 나란히 확인합니다.
두 ID가 담긴 URL은 북마크하거나 새로고침해도 같은 비교를 엽니다.
누락값은 미기록으로 표시하며 0이나 성능 향상으로 추정하지 않습니다.

![독립 대시보드의 실제 Run 비교](images/10-standalone-comparison.png)

## 내보내기와 작은 화면

종료 Run에서 **증거 내보내기 (NDJSON)**를 선택합니다. 전체 관측을 보관하려면 마지막 `end` 레코드와 개수를 확인합니다.
관리되는 JFR/log는 서버의 크기/hash 검증을 거쳐 다운로드되며, 일반 artifact는 참조 전용입니다.
실행 중이거나 크기 제한을 넘는 artifact에는 다운로드를 제공하지 않습니다.

375px 화면에서도 표·버튼을 사용할 수 있으며 키보드의 첫 Tab은 본문 이동 링크입니다.

![375px Run 상세](images/11-standalone-mobile.png)

## 오류와 지원 범위

- 404: Run ID와 연결한 API 서버를 확인합니다.
- 서버 오류: 화면의 오류 문구를 확인하고 **다시 시도**를 누릅니다. 오류를 빈 성공 화면으로 처리하지 않습니다.
- 빈 목록: 아직 Run이 없거나 선택한 실험/페이지에 데이터가 없습니다.
- 현재 단계의 새 화면은 조회용입니다. 기존 실행 폼·프리셋·IO 단건 도구는 기존 화면을 사용합니다.

## 캡처 재현 정보

Playwright로 실제 API에 `dashboard-baseline`/`dashboard-candidate` 큐 실험을 제출해 촬영했습니다.
producer/consumer 각 1개, 입력 20개, 소비 지연 1ms, queue capacity 2/4를 사용했습니다.
개발 중 dirty 코드 상태와 합성 입력으로 만든 UI 검증용 Run이며 성능 Finding이 아닙니다.
API는 전용 임시 DB를 사용했고 데스크톱 1440px/모바일 375px에서 검증했습니다.
`DOC_SCREENSHOTS=1 npm run test:e2e`로 재촬영할 수 있습니다.
