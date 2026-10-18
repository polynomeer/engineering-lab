# 0001. 반복 묶음 소속은 별도 테이블에 둔다

상태: 채택 · 2026-10-18

## 맥락

같은 정의를 여러 번 실행해 분포를 보려면 Run들을 묶어야 한다. Run metadata의 parameter에 `series.id` 같은 값을 넣으면 schema 변경이 없지만, `RunComparison`과 `ComparisonAssessment`가 모든 parameter를 비교하므로 반복 번호가 조건 차이(REVIEW_REQUIRED)로 나타난다. 또한 `RunStore.list`는 metadata 안의 값으로 조회할 수 없다.

## 결정

`lab_series`, `lab_series_run`(이후 `lab_series_baseline`, `lab_sweep`, `lab_sweep_point`)을 schema v4로 추가한다. 각 반복은 그대로 일반 Run이며 Run 행과 metadata는 바꾸지 않는다. 반복 수와 warmup은 정의 hash 밖의 요청 값이다.

## 결과

- 기존 비교·export·Run 화면은 변경 없이 동작하고, 같은 묶음의 Run은 같은 `scenario.definition.sha256`을 가진다.
- v1–v3 저장소는 시작 시 자동 백업 뒤 v4로 이행한다. 이전 서버로 되돌리려면 백업 복원이 필요하다([저장 문서](../run-storage.md#v4-이행)).
- 통계는 저장하지 않고 조회 때 계산하므로 계산 방식이 바뀌면 과거 묶음 결과도 바뀐다. 원시 값은 각 Run summary에 남는다.
