# 로컬 운영과 문제 해결

[문서 안내](README.md) · [사용 가이드](user-guide.md) · [저장 계약](run-storage.md)

이 프로젝트의 현재 배포 단위는 단일 Spring Boot 앱입니다. 인증·다중 사용자 격리·공개 운영 서비스 구성이 포함되어 있지 않습니다. 아래는 개인 로컬 환경에서 실험 증거를 관리하는 절차입니다.

## 1. 실행 방식과 경로

기본 개발 실행:

```bash
./gradlew bootRun --args='--server.address=127.0.0.1'
```

패키징한 실행 파일 사용:

```bash
./gradlew bootJar
```

현재 Gradle root 이름은 `engineering-lab`(v0.2.0까지는 `excel-pipeline`), 버전은 `0.0.1-SNAPSHOT`이므로 아래처럼 실행합니다. 버전이 바뀌었다면 `build/libs/`의 실제 파일명으로 바꿉니다.

```bash
java -jar build/libs/engineering-lab-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1
```

상대 경로 `.lab`·Git directory·시나리오 명령은 실행 위치에 영향을 받습니다. 저장소 루트에서 실행하거나 `--lab.storage.root=/원하는/절대/경로`와 `--lab.storage.git-directory=/저장소/절대/경로`를 명시합니다. jar만 복사한 곳에서는 Git provenance 수집이 실패할 수 있으며 unknown을 clean으로 해석하지 않습니다.

두 번째 앱은 **포트와 저장소 경로를 모두** 바꿉니다.

```bash
./gradlew bootRun --args='--server.address=127.0.0.1 --server.port=18080 --lab.storage.root=build/sandbox-evidence'
./gradlew -q lab --args='list --server http://localhost:18080'
```

두 명령은 별도 터미널에서 실행합니다. `build/` 저장소는 `clean`으로 지워질 수 있는 데모용입니다. 장기 보관은 기본 `.lab/` 또는 별도 영속 디렉터리를 사용합니다. 포트가 열리는지 확인하는 최소 요청은 `GET /experiments`입니다. 전용 actuator health endpoint는 구성하지 않았습니다.

## 2. 정상 종료와 재시작

1. 새로운 실험 제출을 멈춥니다.
2. 서버 터미널에서 `Ctrl+C`를 누릅니다.
3. graceful shutdown·executor/writer 정리와 프로세스 종료를 기다립니다.
4. 같은 저장 경로로 다시 실행합니다.
5. `/runs`에서 과거 Run과 중단 상태를 확인합니다.

여러 종료 단계의 timeout이 합산될 수 있어 `shutdown-timeout-ms` 하나가 전체 앱 종료 시간은 아닙니다. 비협조적 입력/DB I/O를 강제로 취소하지 못할 수 있습니다. 강제 종료 뒤 QUEUED/RUNNING은 재시작 시 INTERRUPTED가 되며 자동 재개하지 않습니다. 부분 summary의 `rows.unconfirmed`, `shutdown.unfinishedExecutors`가 있으면 미확인 작업이 남았다는 의미입니다.

## 3. 무엇을 백업해야 하나

| 대상 | 보관 이유 |
| --- | --- |
| storage root 전체 | `runs.mv.db`, artifact 파일, 연결된 증거 |
| 원본 XLSX | input SHA-256만으로 원본을 복원할 수 없음 |
| 시나리오와 코드 commit | 가설·파라미터·입력 경로·실행 구현 |
| 종료 Run export와 체크섬 | 다른 환경에서도 읽을 수 있는 실행 증거 |
| 필요한 dead-letter 페이지 | NDJSON Run export에는 오류 행 본문이 없음 |

ingestion 메모리 DB 행은 `.lab/` 백업에 포함되지 않습니다. 파일별 수집 상한은 전체 `.lab/` 크기의 상한이 아닙니다. 자동 보관 기간·삭제 기능은 없습니다.

### 백업

앱을 **정상 종료한 후** 저장 디렉터리 전체를 복사합니다. 실행 중 H2 파일 하나만 복사하지 않습니다.

```bash
mkdir -p backups
cp -R .lab "backups/lab-$(date +%Y%m%d-%H%M%S)"
```

`backups/`는 자동 Git ignore 대상이 아닙니다. 개인 백업 경로로 바꾸거나 공유 저장소에 커밋하지 않도록 관리합니다. 실제 입력·오류 셀·실패 메시지·JFR metadata에는 사용한 데이터가 포함될 수 있습니다. 외부 공유 전 내용을 확인합니다.

### 복원

원본을 덮어쓰지 않고 별도 경로에서 확인합니다.

```bash
cp -R /보관한/백업/디렉터리 /복원할/새/디렉터리
./gradlew bootRun --args='--server.address=127.0.0.1 --server.port=18080 --lab.storage.root=/복원할/새/디렉터리'
```

Run ID 조회 → summary/metric 페이지 → JFR/log 다운로드 순으로 확인합니다. 다운로드 파일의 크기·hash를 metadata와 비교합니다. 현재 evidence schema는 **v2**, v1→v2 추가 이행을 지원합니다. 알 수 없는 버전은 시작 시 거부합니다. 다운그레이드는 v2를 이전 앱에서 억지로 열지 말고 업그레이드 전 백업과 해당 코드 버전을 함께 사용합니다.

## 4. 증거 내보내기 검증

```bash
RUN_ID='실제-run-id'
curl --fail --silent --show-error --output run-evidence.ndjson \
  "http://localhost:8080/runs/$RUN_ID/export"
tail -n 1 run-evidence.ndjson
shasum -a 256 run-evidence.ndjson
```

마지막 `type=end`와 Run ID, metricCount/artifactCount를 확인합니다. hash를 Finding에 남기고 원본 artifact도 별도로 내려받습니다. 다운로드 응답은 `Content-Length`, `X-Artifact-SHA256`을 제공합니다. export의 참조만 보관하고 실제 JFR/log 파일을 지우면 다운로드를 복구할 수 없습니다.

## 5. 흔한 문제

| 증상 | 확인·해결 |
| --- | --- |
| JDK toolchain/컴파일 실패 | `java -version`, JDK 25 설치·JAVA_HOME 확인. macOS는 `/usr/libexec/java_home -v 25` |
| 첫 빌드 의존성 다운로드 실패 | 네트워크·프록시 확인. Gradle/Maven 다운로드가 끝난 뒤 재시도 |
| 포트 사용 중 | 기존 앱을 확인하고 새 포트를 지정. CLI `--server`와 브라우저 주소도 일치시킴 |
| 저장소 already owned/lock 오류 | 같은 경로를 사용하는 앱 종료 또는 다른 storage root 사용. 실행 중 lock 파일을 삭제하지 않음 |
| 이전 Run이 안 보임 | 현재 실행 디렉터리·storage root 확인. legacy 목록은 최신 100개이므로 `/runs` 페이지 조회 |
| 재시작 후 DB view가 비어 있음 | 기본 ingestion DB는 메모리 DB. Run 증거와 수명이 다르므로 정상 |
| CLI checksum mismatch | XLSX bytes와 정의 hash 불일치. `shasum -a 256 파일.xlsx`로 갱신 후 validate |
| input path 거부 | 시나리오 디렉터리 내부 상대 경로 사용. `..`, 절대 경로, 외부 symlink 불가 |
| 업로드 413 / 503 | 파일·전체 요청·시나리오 한도 / staging 16개 포화 확인 |
| 압축 관련 parser 실패 | 압축 해제 합계·metadata·entry·행 크기 제한 확인. 단순히 zip 크기만 줄여 해결되지 않을 수 있음 |
| SUCCEEDED인데 오류 행이 있음 | 유효 행 저장과 검증 실패 수집은 함께 가능. dead-letter 페이지 확인 |
| 차트가 비거나 rate=0 | 실행이 250 ms보다 짧거나 preview 밖일 수 있음. 최종 summary·원본 metrics 조회 |
| CLI 대기 timeout | 서버 Run은 취소되지 않음. 이미 반환된 ID를 조회하고 중복 제출하지 않음 |
| 비교에서 dirty/조건 차이 경고 | 기록된 조건이 다름. 입력 hash·Git·관측 설정 확인; UI 오류가 아님 |
| JFR/log 링크가 없음 | 실행 당시 기능 비활성 또는 참조 전용. 활성화 후 새 Run 실행 |
| artifact 404 / 409 | 파일 누락 / 실행 중·경로·크기·hash 불일치. 원본 백업과 참조 대조 |
| artifact 429 / 503 | 동시 다운로드 한도 / SecureDirectoryStream 미지원 파일시스템 확인 |
| 서버는 살아 있으나 새 Run FAILED | failureMessage와 job_id 로그 확인. 실행·관측·저장 실패를 구분 |

실험 실행 화면에서 버튼을 눌러도 접수되지 않으면 브라우저 console과 서버 요청 로그를 확인합니다. 문서 작성 중 발견한 CSV 따옴표 escaping 오류는 `119d1a0`에서 수정했습니다. 오래된 앱을 실행 중이라면 수정 버전으로 재시작하고 화면을 새로고침합니다.

## 6. 저장 용량과 잔여 파일

정기적으로 storage root와 JVM 임시 디렉터리의 여유 공간을 확인합니다. 강제 종료는 staging, `.partial`, `lab-artifact-*.snapshot` 같은 잔여 파일을 남길 수 있습니다. 앱이 종료됐고 진행 중 작업이 사용하지 않는지 확인한 후 해당 파일의 생성 경로와 소유권을 조사합니다. 이 문서는 일괄 삭제 명령을 제공하지 않습니다.

Run/metric 삭제 API나 자동 GC는 없습니다. 장기간 실험을 나누려면 기존 root 전체를 백업하고 새 root로 시작하는 방법을 사용합니다. 기존 DB만 옮기고 artifact 디렉터리를 남겨 두면 참조가 끊어집니다.

## 7. 독립 소켓 IO 실험

Spring 앱과 별도로 실행하는 터미널 실험입니다. 각 서버는 별도 터미널에서 실행하고 부하 클라이언트의 포트를 맞춥니다. 결과는 console에 출력되며 RunStore에 자동 등록되지 않습니다.

```bash
./gradlew runBlockingEchoServer --args='7031 100'
./gradlew runVirtualThreadEchoServer --args='7032 100'
./gradlew runNioSelectorEchoServer --args='7033 true'
```

```bash
./gradlew runIoLoadTest --args='127.0.0.1 7031 300 30'
```

client 인자는 host, port, clients, messagesPerClient입니다. blocking/virtual 인자는 port, delayMs이고 NIO 인자는 port, preserveMessageBoundaries입니다. NIO의 `false`는 의도적으로 메시지 경계 누적을 깨뜨리는 실험이며 성능 우위 비교와 구분합니다. NIO에는 같은 delayMs 인자가 없으므로 위 세 서버의 조건이 동일하다고 해석하지 않습니다. 종료는 각 터미널에서 `Ctrl+C`입니다.
