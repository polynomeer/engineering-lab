# Finding: 저장 대기 시간의 병렬화와 제한 힙에서의 Excel ingestion

상태: 측정·증거 검증 완료 (단일 머신의 제한된 실험)
작성: Codex, 2026-10-04

## Problem / Observed Fact

큐·batch·오류 preview에 상한이 있지만 전체 ingestion의 처리량과 JVM heap은 아직 실측하지 않았다. 병렬 파이프라인이 실제로 단일 스레드보다 빠른 조건과 같은 heap 제한에서 처리 가능한 입력을 확인한다. 운영 장애를 재현한 결과가 아닌 합성 데이터 실험이다.

## Reconstructed Condition

[재현 명령과 측정 범위](../pipeline-benchmark.md)에 따라 `./gradlew pipelineBenchmark`를 실행했다. Java 25.0.1 Amazon Corretto, macOS 26.6.2 aarch64, JVM이 인식한 CPU 10개, 두 모드 모두 `-Xms32m -Xmx128m`이다. CPU 모델·물리 RAM·RSS는 측정하지 않았다.

입력은 3개 셀의 합성 XLSX이며 마지막 셀은 1부터 증가하는 정수다. 지연/기본 H2 실험은 6,000개의 유효 행, 메모리 실험은 10,000/100,000행에서 매 10번째 행의 필수 셀을 비운다. 같은 profile의 두 모드는 동일 파일/hash를 공유한다. H2 file DB의 cache는 8 MiB, 연결은 최대 4개다. 설정은 raw/mapped queue 각각 64, validator 2, inserter 4, batch 100, 오류 preview 16이며 실제 오류 행은 별도 evidence DB에 저장한다.

## Hypothesis / New Design

batch마다 기다리는 시간이 있다면 4개 insert worker가 대기를 겹쳐 처리량을 높일 것이다. 지연 조건의 3회 중앙값이 단일 스레드 이상이면 이 가설을 지지하지 않는다. 지연 없는 H2에서도 개선되는지는 별도로 측정한다.

입력이 10배 늘어도 128 MiB heap 제한에서 성공하고 모든 유효/오류 행이 보존되는지 확인한다. 이것은 해당 크기의 완료 실험이며 임의 크기에 대한 상수 메모리 증명이 아니다. 이번 작업은 측정 도구 추가이며 pipeline 동작이나 성능 최적화는 변경하지 않았다.

## Evidence

- Experiment ID: `pipeline.excel-ingestion`, programmatic scenario ID: `benchmark`. CLI 선언적 scenario 파일 대신 커밋된 `PipelineBenchmark`의 profile을 사용한다.
- 측정 코드: `41628363166162b2de93ec74cd70596c8ef683cb`, 모든 Run의 Git dirty=false. `466e5ab`의 첫 시도는 측정 뒤 macOS AWT 종료 문제로 종료 코드를 실패 처리했고 최종 근거에서 제외했다.
- [통합 원시 측정값](evidence/2026-10-04-pipeline/report.json), [전체 export SHA-256·Run ID·end metricCount 목록](evidence/2026-10-04-pipeline/manifest.json). 총 16개 export의 실제 hash, 성공 상태, end Run ID와 metric 개수를 확인했다.
- 지연 조건 대표 baseline: `5a579d33-414e-4bef-90a2-03e84c27a8d7` ([export](evidence/2026-10-04-pipeline/delayed-SINGLE_THREAD/trial-0.ndjson)).
- 지연 조건 대표 candidate: `d1ce8e8c-3329-4e9e-85a5-3a65d02a912b` ([export](evidence/2026-10-04-pipeline/delayed-PIPELINE/trial-0.ndjson)). 이 두 trial의 시간이 각각 해당 모드 중앙값이다.
- 각 export 첫 Run 레코드에 환경·입력 SHA-256·실제 pipeline 설정이 있다. 별도 benchmark 저장소이므로 앱 기본 `/runs/ui` 비교 URL은 제공하지 않는다. JFR/log artifact는 수집하지 않았다.
- 원본 fixture와 H2 DB/로그: `build/reports/pipeline-benchmark/1791079098069/`. Git에는 보고서와 NDJSON만 보존한다. build 정리 시 입력/DB는 사라진다. 재실행은 같은 행 내용을 생성하지만 XLSX 생성 시각 때문에 파일 hash는 달라질 수 있다.

## Measured Result

각 JVM에서 전체 입력 warmup 1회 후 측정했다. 시간은 staging부터 durable Run 완료까지, 처리량은 오류를 포함한 입력 행/s다. 표의 heap은 측정 trial 중 5 ms 표본 최대값이다.

| 조건 | 단일 스레드 시간 ms | 병렬 시간 ms | 단일 / 병렬 입력 행/s | 단일 / 병렬 heap MiB |
| --- | ---: | ---: | ---: | ---: |
| 6,000행, batch 지연 40 ms, 3회 중앙값 | 2,777.7 | 745.1 | 2,160 / 8,052 | 39.4 / 35.7 |
| 6,000행, 지연 없는 H2, 3회 중앙값 | 220.4 | 227.9 | 27,223 / 26,323 | 49.8 / 47.2 |
| 10,000행, 오류 1,000건, 1회 | 514.1 | 369.4 | 19,451 / 27,074 | 50.6 / 48.1 |
| 100,000행, 오류 10,000건, 1회 | 2,315.2 | 1,420.4 | 43,193 / 70,402 | 76.9 / 82.2 |

지연 조건의 최소–최대는 단일 2,739.2–2,857.7 ms / 병렬 723.3–860.7 ms, 기본 H2는 단일 163.7–263.5 ms / 병렬 173.5–274.0 ms였다. 10만 행은 두 모드 모두 유효 90,000행과 dead-letter 10,000건을 보존했고 preview는 16행이었다. SQL count·고유 번호 count·합계와 dead-letter 전체 페이지를 검증했다. 프로세스는 모두 128 MiB heap 제한에서 종료했고 전체 benchmark 명령도 성공했다.

## Interpretation

40 ms의 batch 대기가 있는 조건에서 중앙값 처리량은 약 3.73배다. 대기 시간을 겹치는 설계 가설을 이 조건에서 지지한다. 지연 없는 H2에서는 병렬 중앙값 시간이 약 3.4% 길어졌다. 변동 폭을 고려하면 일반적 우위나 회귀로 단정할 수 없으며 실제 저장소와 입력에 따라 모드를 선택해야 한다.

입력 10배 증가에서 heap 표본도 증가했다. 큐·오류 preview 상한과 128 MiB 내 완료를 확인했지만 heap 사용량이 입력과 무관하다는 주장은 하지 않는다. 해당 고정 제한에서 합성 입력 10만 행을 처리하는 기준선을 확보했다.

## Limitations / Next Experiment

- 단일 머신·모드별 단일 JVM·고정 순서다. 3회 반복도 신뢰구간이나 통계적 유의성을 제공하지 않는다. 메모리 profile은 1회이므로 처리량 우위 해석에 사용하지 않는다.
- 다른 작업의 CPU/디스크 부하, OS cache, JIT, GC 영향을 분리하지 않았다. H2 cache 제한은 전체 heap 제한과 다르다. HTTP 업로드 전송/서버 실행은 제외하고 동일 staging 및 내부 실행 경로를 사용했다.
- heap 표본은 순간 최대를 놓칠 수 있고 native/RSS/디스크 용량은 포함하지 않는다. queue 표본 0은 무사용을 뜻하지 않는다. 250 ms 미만 Run은 metric 0개일 수 있으며 summary와 외부 시간/heap 표본은 남는다.
- 짧은 셀, 첫 시트, 오류율 10%만 측정했다. 큰 shared strings·긴 셀·다른 오류율·외부 DB는 별도 입력과 가설로 검증해야 한다.
- 재시도/강제 종료/불확실한 커밋 복구는 성능 실험이 아닌 기존 기능 테스트의 범위다. 이 결과로 운영 SLO나 영속 재처리 멱등성을 보장하지 않는다.

## Decision

기본 플랫폼과 Excel 파이프라인의 계획된 최종 검증을 완료한다. 제한 힙·결과 일치·통제된 지연의 처리량 비교를 회귀 테스트로 유지하고 일반 H2 성능 개선 주장은 보류한다. 외부 DB 및 새로운 입력 조건의 최적화는 후속 실험으로 분리한다.
