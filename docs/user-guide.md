# 화면으로 따라 하는 사용 가이드

[README](../README.md) · [문서 안내](README.md) · [API 참조](api-reference.md) · [문제 해결](operations.md)

이 가이드는 서버 시작부터 실험 실행, 두 결과 비교, 증거 내보내기까지 안내합니다. 모든 화면은 jar에 내장된 React 대시보드(`/app`)입니다. 화면의 버튼 이름을 그대로 표기했습니다.

패턴 탐색·Career 실행 등 전체 화면 설명은 [대시보드 사용 가이드](standalone-dashboard.md)를 참고하세요. 기존 Java 내장 화면(`/ui`, `/experiments/ui`, `/ingest/ui`, `/lab/io/ui`, `/runs/ui`)은 제거했으며 해당 주소는 대시보드로 전환됩니다. 이 가이드의 캡처는 제거한 화면을 보여 주어 삭제했고, 대시보드 기준으로 다시 촬영할 예정입니다.

## 1. 서버 시작과 첫 화면

저장소 루트에서 다음을 실행합니다. JDK 25와 Node 24.19.0이 필요합니다. 기본 `bootJar`가 대시보드를 함께 빌드합니다.

```bash
./gradlew test
./gradlew bootJar
java -jar build/libs/engineering-lab-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1
```

서버를 실행한 터미널은 그대로 두고 [http://localhost:8080/app/runs](http://localhost:8080/app/runs)를 엽니다. **실행 기록** 화면이 나오면 준비 완료입니다. `bootRun`과 `-PskipFrontend` jar에는 대시보드가 없으므로 이 가이드에는 사용하지 않습니다.

- **새 실험 실행**: 큐·IO·Excel 실험의 실행 설정을 엽니다(`/app/experiments`).
- **새로고침**: 현재 화면의 목록·상세를 다시 불러옵니다.
- 메뉴의 **현재 DB 조회**: 현재 서버의 입력 데이터 테이블을 봅니다(`/app/tools/ingestion-db`).
- 메뉴의 **IO 단건 데모**: 브라우저에서 IO endpoint를 한 번씩 호출합니다(`/app/tools/io-echo`).

첫 실행에는 목록이 비어 있어도 정상입니다.

## 2. 큐 경합 실험 실행

[실험 실행](http://localhost:8080/app/experiments?experiment=concurrency.queue-contention) 화면을 엽니다. **실험 선택**에서 큐 경합 실험을 고르고 **설정 열기**를 눌러도 됩니다. **실행 조건**에 다음 값을 사용합니다.

| 입력 | 값 | 의미 |
| --- | ---: | --- |
| 시나리오 ID | `queue-contention-demo` | 실행 조건을 부르는 이름 |
| 가설 | 자유 입력 | 이 실행으로 확인할 내용 |
| queueCapacity | 2 | 대기 가능한 항목 수 |
| producerThreads | 2 | 생산자 수 |
| consumerThreads | 1 | 소비자 수 |
| itemsPerProducer | 100 | 생산자 한 개가 생성할 항목 수 |
| consumerDelayMs | 2 | 소비 항목별 인위적 대기 |

**실험 실행**을 누릅니다. 접수되면 **접수된 실행 상세 보기** 링크가 표시되고, 상세에서 RUNNING → SUCCEEDED를 확인할 수 있습니다. 실행 중인 Run은 상세 화면이 자동으로 다시 조회합니다. 성공 시 produced/consumed는 각각 200입니다.

**queue-quick-demo**, **queue-backpressure-stress**, **queue-burst-producers**는 입력값을 바꾸는 기본 프리셋입니다. 적용한 값을 확인한 뒤 실행 버튼을 누릅니다. 최대 큐 깊이·평균 enqueue 대기·blocking 횟수로 소비자가 느릴 때의 배압을 관찰합니다. 한 번 실행 결과만으로 일반적인 성능 우위를 판단하지 않습니다. 같은 조건을 여러 번 실행하려면 **반복 측정**을 사용하고 결과는 [반복 측정](http://localhost:8080/app/series) 화면에서 봅니다.

## 3. IO 실험: 저장되는 실행과 단건 데모

### 실행 증거를 남기는 방법

[실험 실행](http://localhost:8080/app/experiments?experiment=io.endpoint-comparison)에서 IO endpoint 비교 실험을 열고 requests=12, delayMs=20, pinning=false, pinDelayMs=20으로 **실험 실행**을 누릅니다. 성공하면 virtual/reactive done이 각각 12이며 Run은 저장소에 남습니다.

CLI로도 같은 실험군을 실행할 수 있습니다.

```bash
./gradlew -q lab --args='run experiments/io/endpoint-comparison/scenarios/baseline.json --wait'
```

### 브라우저에서 한 번씩 호출하는 방법

[IO 단건 데모](http://localhost:8080/app/tools/io-echo)에서 메시지=`hello`, 지연 (ms)=20, Pinning=false를 입력하고 **Virtual thread 요청** 또는 **Reactive 요청**을 누릅니다.

요청마다 시각·방식·왕복 ms·응답이 표에 추가됩니다. 이 화면은 Run을 만들지 않으며 최근 20개 응답을 이 주소의 브라우저 저장소에만 보관합니다. **데모 CSV**로 현재 응답 목록을 내려받습니다. 반복 요청 부하의 비교는 위의 IO endpoint 비교 실험을 사용합니다.

Pinning 옵션은 synchronized 안의 대기를 켜는 실험 조건입니다. 이름이나 왕복 시간 차이가 JDK 동작의 원인 분석을 대신하지 않습니다. 브라우저 왕복 시간과 서버 내부 실험 지표도 측정 범위가 다릅니다.

## 4. Excel 실행

### 포함된 fixture 사용

다른 터미널에서 아래 명령을 실행합니다.

```bash
./gradlew -q lab --args='validate experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml'
./gradlew -q lab --args='run experiments/pipeline/excel-ingestion/scenarios/small-batch.yaml --wait'
```

유효 데이터 5행이므로 최종 summary는 produced=5, inserted=5, validationErrors=0입니다. 접수 응답의 `runId`를 메모합니다.

### 자신의 파일 업로드

A열 필수 문자열, B열 필수 문자열, C열 정수로 첫 시트를 작성하고 첫 행에 헤더를 둡니다. 파일은 `.xlsx`여야 합니다. 작은 샘플을 만듭니다.

```bash
./scripts/create-test-xlsx.sh build/test-data/sample.xlsx 500
```

[Excel 실험 실행](http://localhost:8080/app/experiments?experiment=pipeline.excel-ingestion) 화면에서 두 방법으로 업로드합니다.

- **Excel 업로드**: 파일과 모드(`PIPELINE` 또는 `SINGLE_THREAD`)를 고르고 **업로드 실행**을 누릅니다. 접수되면 **Excel 작업 상세 보기** 링크가 표시됩니다.
- **실행 조건**: **입력 워크북 (.xlsx)**에 파일을 올리고 시나리오 ID·가설·설정을 입력한 뒤 **실험 실행**을 누릅니다. 브라우저가 SHA-256을 계산해 정의에 기록하며 반복 측정·스윕에도 같은 파일을 씁니다.

API로 직접 올릴 수도 있습니다.

```bash
curl --fail --silent --show-error \
  -F 'file=@build/test-data/sample.xlsx' \
  'http://localhost:8080/ingest/excel?mode=PIPELINE'
```

`mode=SINGLE_THREAD`로 바꾸면 단일 스레드 실행입니다. 다시 업로드하면 새 Run을 생성하고 같은 행이 다시 저장될 수 있습니다.

### 결과 확인

**Excel 실행 기록**(`/app/runs?experiment=pipeline.excel-ingestion`)에서 상태를 확인하고 Run을 엽니다. 상세의 **Excel 처리 현황**에서 파일·모드와 읽은 행·저장한 행·검증 오류를 확인합니다.

짧은 실행은 250 ms 진행 표본이 거의 없거나 없을 수 있습니다. 빈 차트나 0 rate만으로 저장 실패를 판단하지 말고 최종 상태와 summary를 확인합니다. `SUCCEEDED`여도 검증 오류가 있을 수 있습니다.

[현재 DB 조회](http://localhost:8080/app/tools/ingestion-db)는 실제 현재 데이터 테이블입니다. **최신 행 수**를 바꾸고 **DB 조회**를 눌러 조회 수를 조절합니다.

Run의 합계 inserted와 현재 DB 행 수는 다를 수 있습니다. Run 증거는 파일 DB에 유지되지만 기본 ingestion DB는 메모리 DB여서 서버를 재시작하면 초기화됩니다. 이 화면은 Run별 필터가 아닌 현재 테이블 전체 조회입니다.

## 5. 오류 행 조회

실패 행을 보고 싶다면 샘플 복사본에서 A2를 비우거나 C2를 `not-an-integer`로 바꿔 새로 업로드합니다. 원본 fixture를 수정하면 선언적 시나리오의 checksum도 갱신해야 하므로 이 연습은 위 multipart 업로드로 진행합니다.

실행이 끝나면 반환된 ID를 아래에 넣습니다.

```bash
RUN_ID='실제-run-id'
curl --fail --silent --show-error \
  "http://localhost:8080/runs/$RUN_ID/dead-letters?after=-1&limit=100"
```

`rows`에는 `rowIndex`, `stage`, `reason`, `cells`가 있습니다. rowIndex는 0 기반이므로 실제 Excel 행 번호는 **rowIndex + 1**입니다. `nextAfter`가 있으면 다음 요청의 `after`에 넣어 끝까지 조회합니다. 실행 중 조회는 409입니다. 대시보드에서는 Excel Run 상세의 **Dead-letter** 탭에서 같은 내용을 봅니다.

preview는 기본 100행까지지만 전체 오류 집계와 영속 오류 행은 별도입니다. 저장소 오류나 행당 64 KiB 초과가 발생하면 Run 자체가 실패합니다.

## 6. 실행 기록에서 상세 읽기

[실행 기록](http://localhost:8080/app/runs)을 엽니다. **실험 필터**, **시나리오 ID 시작**, **상태**를 고르고 **기록 조회**를 누르면 서버에서 조건에 맞는 Run만 조회합니다. **이전 실행**/**최신 실행**으로 목록 페이지를 이동하고 Run을 눌러 상세를 엽니다.

상세는 **전체**, **검증 결과**, **메트릭**, **실행 환경**, **아티팩트** 탭으로 나뉩니다. Excel Run에는 **Dead-letter** 탭이 추가됩니다. 다음 순서로 읽습니다.

1. **상태**: 성공 여부와 실패 이유를 확인합니다.
2. **가설**: 실행 시 기록한 검증 대상입니다. 기존 POST API로 실행한 Run에는 없을 수 있습니다.
3. **입력과 실행 환경**: Java/OS/heap/CPU와 Git commit·dirty, 실제 설정·입력 hash를 확인합니다.
4. **원본 메트릭 요약**: 최종 행 수·오류·재시도 등 집계를 확인합니다.
5. **관측 기록 / 첨부 증거**: 원본 metric 페이지와 파일을 봅니다.

실행 중인 Run은 상세 화면이 자동으로 다시 조회하며, 상단 **새로고침**으로 직접 갱신할 수도 있습니다. 주소 `/app/runs/<id>`는 같은 evidence 저장소를 사용하는 서버에서 다시 열 수 있습니다. 서버나 저장소가 달라지면 같은 주소의 ID를 찾지 못할 수 있습니다.

## 7. 두 Run 비교

1. baseline으로 사용할 Run 상세에서 **비교 기준으로 선택**을 누릅니다. [비교](http://localhost:8080/app/compare) 화면의 **기준 Run ID**가 채워집니다.
2. **비교 Run ID**에 candidate Run ID를 붙여 넣고 **비교하기**를 누릅니다. 주소는 `/app/compare?baseline=<id>&candidate=<id>`입니다.
3. 경고와 **비교 조건 확인**의 조건 차이를 먼저 읽습니다.
4. 측정값의 이름·단위·누락 여부를 확인하고 **변화율**을 해석합니다.
5. **비교 요약 CSV**, **비교 JSON**, **Finding 초안 (.md)**, **증거 묶음 (.zip)**으로 결과를 보관합니다.

UI 제출과 선언적 CLI 제출을 비교하면 시나리오 정의·Git 상태 차이 경고가 표시될 수 있습니다. 화면 오류가 아니라 비교의 전제 조건을 알리는 표시입니다. dirty=true인 Run은 성능 Finding의 깨끗한 커밋 측정과 구분합니다.

조건이 다른 결과를 곧바로 개선율로 표현하지 않습니다. 같은 입력 checksum·환경·관측 설정으로 비교하고 반복 측정합니다. 이 화면은 자동으로 승자를 정하지 않습니다.

## 8. 증거와 관측 파일 보관

종료된 Run 상세의 **증거 내보내기 (NDJSON)**를 누르면 Run·원본 metric·artifact 참조를 내려받습니다. **증거 묶음 (.zip)**은 export와 확인된 캡처·SHA256SUMS를 한 파일로 묶습니다. 마지막 줄에 `type=end`가 있어야 하며 metricCount/artifactCount를 확인합니다. 원본 XLSX·dead-letter 전체·JFR/log 파일은 이 export에 포함되지 않습니다.

JFR·로그는 **실험 실행 전 서버에서 활성화**해야 합니다. 기본 실행에서는 목록이 비어 있는 것이 정상입니다.

```bash
java -jar build/libs/engineering-lab-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 \
  --lab.observation.jvm-enabled=true --lab.observation.jvm-interval-ms=1000 \
  --lab.trace.enabled=true --lab.jfr.enabled=true --lab.logs.enabled=true
```

기존 서버를 정상 종료한 뒤 실행하고 새 Run을 만듭니다. 과거 실행의 파일을 소급 생성하지 않습니다.

**아티팩트** 탭의 **다운로드 ↗**는 파일 크기·SHA-256을 서버에서 확인한 후 내려받습니다. 새 탭에서 오류가 보이면 [운영 문서](operations.md)의 artifact 항목을 확인합니다. 임의의 사용자 제공 참조에는 다운로드가 지원되지 않을 수 있습니다.

SHA-256 확인 예:

```bash
shasum -a 256 run-evidence.ndjson
# Linux에서는 sha256sum run-evidence.ndjson
```

[Finding 템플릿](findings/TEMPLATE.md)에 가설·조건·Run ID·export 경로/hash·관측·해석·한계를 기록합니다. 캡처는 사용법 설명 자료이며 성능 증거의 원본을 대신하지 않습니다.

## 9. 종료와 다음 실행

서버 터미널에서 `Ctrl+C`를 누르고 종료 로그를 기다립니다. `.lab/`을 유지한 채 같은 위치에서 다시 실행하면 과거 Run을 조회할 수 있습니다. 백업·복원·다른 저장소 사용은 [운영 문서](operations.md)를 참고합니다.

별도 JVM의 반복 측정은 `./gradlew pipelineBenchmark`로 실행하며 [benchmark 가이드](pipeline-benchmark.md)에 측정 범위가 있습니다.

## 캡처 재현 정보

대시보드 캡처는 frontend 브라우저 테스트를 `DOC_SCREENSHOTS=1`로 실행할 때만 [images](images/)에 갱신되며, 실행 방법은 [frontend 안내](../frontend/README.md)를 따릅니다. 캡처는 합성 데이터로 작성합니다.

문서용 서버는 기존 `.lab/`과 분리합니다.

```bash
java -jar build/libs/engineering-lab-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=18080 \
  --lab.storage.root=build/docs-demo --lab.observation.jvm-enabled=true --lab.observation.jvm-interval-ms=1000 \
  --lab.trace.enabled=true --lab.jfr.enabled=true --lab.logs.enabled=true
```

이 서버를 따라 실행하면 본문의 URL을 18080으로, CLI에 `--server http://127.0.0.1:18080`을 적용합니다. 문서용 데이터는 `build/` 산출물이므로 영구 보관용이 아닙니다. 캡처 파일은 [images](images/)에 저장돼 Git에서 함께 관리됩니다.
