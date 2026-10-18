# Batched checksum — 패턴 재사용 사례 (P6)

**NEW-DESIGN**: Excel·Equity와 다른 합성 레코드 생성 사례다. 실제 업무/DB 처리 성능을 재현하지 않는다.
`seed + index` 값을 한 건씩 생성하고 P5의 `BoundedBatch`를 통해 동기 checksum sink에 전달한다.
입력 전체 List, 외부 DB, worker image, 추가 executor를 만들지 않는다.

기존 `chunked-write`와 `streaming` 패턴을 연결하고, 사례·전략·시나리오·측정 계약은 이 패키지의
독립 contribution bean으로 등록한다. 중앙 controller, registry, frontend 분기는 추가하지 않는다.
기본값은 비활성이며 활성화하면 기본 3개에 한 사례가 추가된다. Career 13개를 모두 활성화한
환경에서는 총 17개가 된다. 운영 배포를 자동 변경하지 않는다.

```bash
JAVA_HOME="$(/usr/libexec/java_home -v 25)" ./gradlew bootRun \
  --args='--lab.examples.checksum.enabled=true --server.port=18095 --lab.storage.root=/tmp/lab-checksum-example'
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  --data-binary @experiments/examples/batched-checksum/scenarios/partial-batch.json \
  http://localhost:18095/runs
```

독립 UI는 `LAB_API_TARGET=http://127.0.0.1:18095 npm --prefix frontend run dev`로 연결하고
`/app/catalog?pattern=chunked-write`에서 **Batched checksum**을 선택한다. 기존 schema 폼에서
`examples.batched-checksum`을 실행하거나 사례 설명의 JSON을 내려받아 위 HTTP API로 제출한다.
사례 프리셋 다운로드는 폼에 자동 적용되지 않는다. Spring 선택 등록이므로 기본 ServiceLoader만
읽는 오프라인 CLI validate에는 이 사례가 없다. CLI 전용 provider를 추가하지 않고 HTTP로 검증한다.

| 파라미터 | 범위 | 의미 |
| --- | --- | --- |
| rows | 1–10000 | 생성 레코드 수 |
| batchSize | 1–256 | 보관/쓰기 단위 상한 |
| seed | 0–1000000 | 첫 레코드 값; 난수 아님 |
| failBatch | 0–10000 | 1부터 시작하는 배치 번호; 0은 실패 없음, 실제 배치 수 초과는 도달하지 않음 |

세 프리셋은 rows=23, seed=7을 사용한다.

- `row-write`: batchSize=1. 23회 sink 호출, rows=23, checksum=414.
- `partial-batch`: batchSize=8. 8/8/7 tail까지 3회 호출, 같은 rows/checksum.
- `sink-failure`: batchSize=8, failBatch=2. 두 번째 sink가 쓰기 전에 실패하고 Run은 FAILED.
  첫 성공 배치의 rows=8/checksum=84는 timeline에 남으며 후속 쓰기/자동 재시도는 없다.

측정은 checksum.rows/value/batches/maxBatchRows 누적/최대 snapshot이다. 시간·처리율 지표는 없으며
행별/배치 전략의 속도 우위를 주장하지 않는다. 두 성공 Run의 batchSize/정의 hash가 다르면 P4 비교는
조건 차이를 표시한다. 기대 checksum은 `rows * seed + rows * (rows - 1) / 2`다. 합은 충돌 가능하므로
일반적인 데이터 무결성 증명이 아니다. 실제 DB commit이나 idempotency를 검증하지 않는다.

실패 Run의 최종 summary가 비어 있어도 이전 timeline은 증거로 보존한다. metrics sink 자체의 실패도
전파한다. 모든 입력은 생성된 정상 정수이므로 행 검증/DLQ는 적용하지 않는다. retry·transaction 정책을
추가하지 않으며 cooperative interruption은 기존 runner의 상태/종료 처리에 전달한다.

[확장 가이드](../../../docs/adding-experiments.md)의 P6 체크리스트와
[공통 배치 계약](../../../docs/pipeline-contract.md)을 따른다.
