# Finding: <검증할 주장>

상태: 초안 / 검토됨 / 보류
작성자 및 날짜:

## Problem / Observed Fact

실제 관측한 문제와 출처. 회상·추정과 직접 관측을 구분한다.

## Reconstructed Condition

재현 환경과 원래 환경의 차이, 입력 생성 방법, 제외한 조건.

## Hypothesis / New Design

검증 전 가설, 변경한 변수, 기대하는 결과와 반증 기준.

## Evidence

- Experiment ID:
- Scenario 파일 / Git commit / 정의 SHA-256:
- Baseline Run ID / `/app/runs/<id>`:
- Candidate Run ID / `/app/runs/<id>`:
- 비교 URL `/app/compare?baseline=<id>&candidate=<id>`:
- 각 Run의 상태·Git commit/dirty·JVM/OS·heap/CPU·input SHA-256:
- Export 보관 위치 / SHA-256 / 마지막 end 레코드와 metricCount 확인:
- Artifact ID / 보관 위치 / SHA-256 / 원본 파일 검증 방법:

Run ID는 해당 `.lab` 저장소 안에서 추적한다. URL만으로 영구 보존되지 않으므로 export와 원본 artifact를 함께 보관한다. 민감 데이터는 검토 후 공유한다.

## Measured Result

| Metric / 단위 | Baseline | Candidate | 반복 횟수 / 변동 | 증거 위치 |
| --- | --- | --- | --- | --- |
| <측정값> | | | | |

실제로 기록된 값만 적는다. 미측정값은 미측정으로 두고 0으로 치환하지 않는다.

## Interpretation

관측으로 지지/반박되는 가설과 그 근거. 인과관계가 입증되지 않은 설명은 해석으로 표시한다. SINGLE_PASS 한 번의 실행을 일반적인 성능 우위로 주장하지 않는다.

## Limitations / Next Experiment

환경·입력·단위 차이, warmup/반복 부족, 실패/중단·누락 데이터, 다른 설명 가능성, 후속 실험.

## Decision

적용/보류할 결정과 적용 범위. 관측 사실과 새 설계 제안을 분리한다.
