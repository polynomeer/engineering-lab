# Excel ingestion 처리량·힙 검증

```bash
./gradlew test
./gradlew pipelineBenchmark
```

`PipelinePerformanceTest`는 같은 XLSX 6,000행(오류 10%)을 별도 `-Xms32m -Xmx128m` JVM에서 실행한다. 모드마다 전체 입력 warmup 1회 후 3회 측정한다. batch당 60 ms 지연을 주어 병렬 저장이 대기 시간을 겹치는 조건의 중앙값 개선을 검증한다. 일반 H2 처리량 개선을 보장하는 테스트가 아니다.

수동 benchmark는 `build/reports/pipeline-benchmark/<실행시각>/`에 fixture, 모드별 `case.json`, 표준 Run NDJSON export, H2 ingestion/evidence DB와 process 로그, 통합 `report.json`을 보관한다. 하위 프로세스 실패·180초 timeout·데이터 검증 실패는 전체 명령 실패다. 일반 테스트의 파일은 임시 디렉터리에서 정리된다.

## 고정 조건

- `delayed`: 6,000행, 오류 없음, batch당 40 ms, warmup 1회 + 측정 3회.
- `native`: 같은 행 구성, 지연 0, warmup 1회 + 측정 3회.
- `memory-small` / `memory-large`: 10,000 / 100,000행, 오류 10%, 지연 0, 각각 warmup 1회 + 측정 1회.
- profile별로 생성한 동일 XLSX 파일을 두 모드가 공유한다. SHA-256은 case와 Run metadata에 기록한다. fixture 생성은 측정 JVM 밖에서 streaming window 32행으로 수행한다.
- raw/mapped queue 각각 64행, validator 2개, inserter 4개, batch 100행, 시간 flush 1,000 ms, offer timeout 100 ms, 오류 preview 16행. 나머지는 Run metadata의 실제 설정을 확인한다.
- ingestion은 H2 file DB, cache 8 MiB, Hikari connection 최대 4개다. evidence는 별도 `JdbcRunStore`, metric queue 32/batch 16이다. 오류는 실제 evidence DB에 동기 저장한다. retry/dead-letter를 생략하지 않는다.
- 모드마다 독립 JVM·DB를 사용한다. trial 사이 ingestion 테이블을 비우고 evidence는 유지한다. 실행 순서는 profile 목록 순, 각 profile 내 `PIPELINE` → `SINGLE_THREAD` 고정이다.

## 측정 범위와 검증

`wallMs`는 입력 임시 파일 staging/hash → durable Run 생성 → parsing/validation/JDBC 저장/dead-letter → metric writer 종료와 결과 영속화 → 입력 정리를 포함한다. fixture 생성, JVM/DB 초기화, 테이블 비우기, 최종 데이터 검증·export는 제외한다. `inputRowsPerSecond`는 오류를 포함한 입력 행 수 / wall 시간이다. Run summary의 `timing.elapsedMs`는 더 좁은 pipeline 내부 시간이다.

5 ms 간격 MemoryMXBean 표본의 최대 heap used, JVM 최대 heap, GC 누적 시간 차이, pipeline 진행 callback의 queue 최대 표본을 기록한다. 표본 최대는 순간 최대의 하한이고 queue의 250 ms 표본은 짧은 포화를 놓칠 수 있다. `-Xmx128m`은 JVM heap 제한으로 native memory/RSS/디스크 상한을 뜻하지 않는다. warmup을 포함한 프로세스가 제한 안에서 완료한 사실과 작은 입력/큰 입력의 결과를 함께 해석한다. 이를 임의 크기 입력의 상수 메모리 증명으로 해석하지 않는다.

각 trial은 성공 상태, produced/inserted/error 수, preview 상한, SQL 저장 수·고유 번호 수·번호 합, 전체 dead-letter 페이지 수, queue 표본 상한을 확인한다. NDJSON에는 Run ID, Git SHA/dirty, JVM/OS/CPU/heap, 입력 hash, 설정, metrics, 종료 레코드가 있고 case에 export SHA-256을 기록한다. UI 서버의 기본 `.lab` DB와 별도 저장소이므로 해당 Run이 기본 실행 기록(`/app/runs`)에 나타나지는 않는다.

단일 머신·고정 순서·제한된 반복이다. 디스크 캐시와 JIT/GC/백그라운드 부하 영향을 분리하지 않으며 통계적 유의성이나 운영 DB 성능을 주장하지 않는다. 다음 최적화는 native 결과에서 병목을 확인한 후 별도 가설로 진행한다.
