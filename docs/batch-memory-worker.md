# Batch / Excel memory worker (D5-3)

## 실행과 해석

`career.batch-excel.batch-memory`와 `career.batch-excel.excel-memory`를 공통 대시보드의
CONTAINER 실행, 중앙 Run 저장, CHECK/메트릭/REPORT와 확장 export에 연결한다.
Java 21 worker는 기존 Spring Batch tasklet/chunk job과 XSSF/SXSSF exporter를 호출한다.
기존 독립 앱과 JUnit benchmark는 유지한다.

| 실험 | BEFORE | AFTER |
| --- | --- | --- |
| batch-memory | 전체 조회 tasklet | 1,000행 chunk job |
| excel-memory | 전체 조회 + XSSFWorkbook | 2,000행 페이지 + SXSSF 500행 창 |

두 실험 모두 `FAIL_AFTER_MEASUREMENT`를 제공한다. AFTER 작업과 결과 검증을 마친 뒤
최종화 실패를 의도적으로 기록한다. 측정값과 REPORT는 남지만 Run은 FAILED이고
증거는 미완료로 유지한다. 이는 부분 증거 경로 검증이며 batch rollback/restart 검증이 아니다.
기존 처리 방식은 FACT 기반이고 작은 합성 fixture·실행 경계·측정 계약은 NEW-DESIGN이다.
과거 업무 성능이나 개선율을 재현하는 결과로 해석하지 않는다.

각 variant는 **독립 Run, 새 컨테이너/JVM, 새 MySQL**에서 한 번 실행한다. 비교할 때
rows·seed·이미지·heap 한도를 같게 유지한다. `variant`, `rows`(1–5,000),
`seed`(0–2,147,483,647)를 모두 지정한다. SINGLE_PASS만 허용하며 파일 입력은 없다.
화면과 [JSON 예제](../experiments/career/batch-excel/scenarios/) 기본값은 2,001행/seed 42다.
정산월과 생성일은 2026-01로 고정하고 1,000행 이하 JDBC batch로 결정적 데이터를 만든다.

## 측정 범위와 증거

MemoryMXBean의 heap used를 20ms 간격과 시작/종료 경계에서 읽는다. 측정 이력을 메모리에
쌓지 않고 baseline·최대 관측값·샘플 수만 보관한다. 명시적 GC를 호출하지 않는다.
Spring/schema 초기화와 seed 이후 실제 `JobLauncher.run` 또는 `export`만 측정한다.
결과 검증·정리는 제외하며 warmup 없는 cold 실행이다. 실행 전 생성된 garbage도 관측에
포함될 수 있고 샘플 사이의 순간 최대치는 놓칠 수 있다. **sampled peak는 true peak,
live set, RSS가 아니다.** 작은 입력에서 AFTER가 더 느리거나 더 많은 heap을 관측할 수
있으므로 성능 순서 assertion을 두지 않는다. 기존 `MemoryUsageProbe` benchmark의
명시적 GC 기반 측정과도 직접 섞어 비교하지 않는다.

| 메트릭 (`memory.` 접두사) | 의미 |
| --- | --- |
| baselineBytes | 작업 시작 시 heap used |
| sampledPeakBytes | 경계와 주기 샘플 중 최대 heap used |
| sampledDeltaBytes | sampledPeakBytes − baselineBytes |
| samples | 시작/종료를 포함한 샘플 수 |
| elapsedNanos | 측정 구간 경과 시간 |
| heapLimitBytes | 실제 Runtime.maxMemory() |
| processedRows | 검증한 집계 또는 Excel 데이터 행 수 |
| outputBytes | Excel 출력 파일 크기; batch에는 없음 |

모든 값은 RUN_FINAL이며 합산하지 않는다. `-Xmx128m`과 JVM이 보고하는 maxMemory는
collector 정렬 때문에 정확히 같지 않을 수 있다. `measurement-window` CHECK와
`memory-report.json`에는 seed·행 수·샘플 간격/수·heap 한도·seed 완료 및 측정 시작/종료
시각을 남긴다. REPORT는 JVM 식별자와 chunk/page/window도 포함한다.
정상 실행은 seed-count, measurement-window, workload-completed, output-integrity의
네 CHECK를 생성한다. batch는 집계 행 수와 gross 합계를, Excel은 SAX readback으로
모든 행의 여섯 셀을 seed와 대조한다. readback은 측정 밖에서 수행한다.

Excel 출력은 검증 후 삭제한다. REPORT는 중앙 사본으로 보존되지만 확장
NDJSON export에는 참조를 제공하고 화면에서 중앙 사본을 다운로드한다. D6 관리 REPORT 다운로드를 지원한다. 실패 실행도 확인된
CHECK/메트릭/REPORT를 조회할 수 있고 동일 저장소 재개방 후 그대로 유지한다.

## 배포와 자원 관리

```bash
./scripts/career-lab.sh test :batch-excel:memoryWorkerManifest
```

manifest는 `career-lab/batch-excel/build/libs/memory-worker-manifest.json`이다.
[공통 이미지 계약](worker-container-isolation.md)에 따라 Java 21 JRE 이미지의
`/opt/lab/worker.jar`에 배포 jar를 배치한다. worker의 requiresDocker=false는 worker 안에
Docker client가 없다는 뜻이다. controller에는 Docker와 로컬에 준비된 이미지가 필요하다.
통합 검증은 eclipse-temurin:21-jre와 mysql:8.0.39를 전체 image ID로 고정한다.

```yaml
lab:
  career:
    memory:
      enabled: true                     # 기본 false
      recover-on-startup: false          # 기본 false
      container:
        image-id: sha256:<64자리 worker image ID>
        artifact-sha256: <64자리 jar SHA-256>
        mysql-image-id: sha256:<64자리 MySQL image ID>
```

빈 기본 image/hash를 채운 뒤 활성화한다. 미준비 이미지나 미해결 실행은 접수를 차단한다.
화면 `/app/experiments/career.batch-excel.batch-memory` 또는 `excel-memory`에서 preset을
선택하고 실행 상세의 원본 관측·CHECK·확장 내보내기를 확인한다.

`BatchExcelContainerResources`는 공통 `CareerContainerResources`를 사용한다.
실행별 내부 network와 MySQL 512MiB/data tmpfs 256MiB를 만들고 고정 alias batch-db와
임시 암호를 전달한다. worker는 공통 256MiB 컨테이너/128MiB JVM heap 제한을 사용하며
Docker socket·외부 포트가 없다. journal은 `batch-excel-mysql-resources`에 저장한다.
확인된 worker → DB → 비어 있는 network 순서로 제거하고, ID·소유권·engine·profile이
일치하지 않거나 생성/시작 결과가 불명확하면 자동 제거하지 않고 접수를 차단한다.
시작 시 복구는 opt-in이며 기존 Run을 재실행하거나 미완료 증거를 성공 처리하지 않는다.
공통화한 equity adapter의 기존 namespace/profile/환경 변수는 유지한다.

## 재현 검증

공유 worker-support 산출물이 있으므로 두 Gradle wrapper를 동시에 실행하지 않는다.

```bash
./scripts/career-lab.sh test :batch-excel:memoryWorkerManifest :equity-system:equityWorkerManifest
./gradlew test memoryContainerTest equityContainerTest
```

memoryContainerTest는 schema 거절 → 두 실험의 세 variant → CHECK/원본 메트릭/REPORT/
확장 export → 자원 정리 → 동일 저장소 재개방을 실제 컨테이너로 확인한다.
API 응답은 `build/verification/d5-3`에 저장한다. equityContainerTest는 공통 자원 코드
추출 후 기존 세 실험의 일곱 실행을 회귀 검증한다.

Node 버전은 [frontend README](../frontend/README.md)를 따른다. frontend 서버 실행 후:

```bash
cd frontend
npm test
npm run build
MEMORY_EVIDENCE=../build/verification/d5-3 npm run test:e2e -- memory.spec.ts
```

브라우저 테스트는 실제 API 응답 snapshot 여섯 개와 두 preset 폼을 검증한다.
브라우저가 직접 실제 Docker 실행을 접수하는 테스트와 구분한다. MEMORY_EVIDENCE를
생략하면 snapshot 테스트는 명시적으로 건너뛴다.

최종 검증 (2026-10-15): `./scripts/career-lab.sh test :batch-excel:memoryWorkerManifest`와
equity manifest 갱신 후 `./gradlew test memoryContainerTest equityContainerTest`가 성공했다.
Career 140건(일부 미변경 결과 재사용), 루트 338건, 실제 memory 6건·equity 7건,
frontend 단위 17건·타입 검사·빌드, 실제 응답 6종·preset 2종·기존 Career 화면 4종의
브라우저 12건이 모두 통과했다. 선택 실행인 기존 실서버 브라우저 2건은 제외했다.
375px 가로 넘침 검증과 desktop/mobile 캡처를 확인했다. 브라우저 초기 서버 바인딩
권한 문제는 권한을 갖춰 재시작해 해소했고, 실제 176자 측정 조건이 접기 대상이라는
테스트의 잘못된 가정은 관측 원문 전체 표시 검사로 수정했다. 최종 검증은 건너뜀 없이 통과했다.

테스트 소유 worker·의존 컨테이너·network·임시 이미지 제거를 확인하고 Vite 서버를 종료했다. 다른 작업의 Docker 자원은 유지했다.

D6의 현재 동작·제한·운영 명령은 [통합 운영 가이드](dashboard-operations.md)를 따른다.
