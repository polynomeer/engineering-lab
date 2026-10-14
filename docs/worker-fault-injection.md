# D4 실패 주입 검증 표

D4의 완료 기준은 프로세스 종료 코드만으로 성공하지 않고, protocol·저장·종료·정리 확인이
끝나야 증거를 완료 처리하는 것이다. 아래 표는 codec 대역, 실제 Java 21 자식, 실제 Docker
경계와 중앙 H2 저장소를 구분한다. 모든 장애를 모든 화면에서 다시 주입하는 검증은 아니다.

주요 소스: [LOCAL_JVM 통합](../src/test/java/com/pnomeer/lab/runtime/LocalJvmExecutorTest.java),
[컨테이너 통합](../src/careerTest/java/com/pnomeer/lab/runtime/ContainerWorkerIntegrationTest.java),
[실제 프로세스 transport](../career-lab/worker-support/src/test/java/com/pnomeer/lab/worker/WorkerProcessTest.java),
[실패 주입 fixture](../career-lab/worker-support/src/test/java/com/pnomeer/lab/worker/ProcessFixture.java).

## 요구사항과 검증 위치

| 요구사항 | 실제 실행 / 저장 경계의 증거 | 별도 단위 검증 / 범위 |
| --- | --- | --- |
| malformed JSON | `LocalJvmExecutorTest.wireFailureSurvivesStoreReopenAndStopsActualWorker`, `ContainerWorkerIntegrationTest.failuresDoNotSealAndStillRemoveContainer`: FAILED·미완료 증거·재개방·자식 종료/경계 제거 | `WorkerProtocolTest.rejectsSharedFailureFixtures` |
| oversize 레코드 | 위 두 테스트의 `oversize`: 65,537바이트 출력 거절, HELLO 이전 worker identity 없음 | `boundedFramingRejectsOversizeTotalLimitEventLimitAndTruncation`: 레코드/총량/event 상한 |
| event 순번 누락 | `sequence-gap`: HELLO 다음 순번 1 대신 2를 실제 pipe로 전달하고 거절 | validating Writer를 우회한 wire fixture이며 수신자가 검증 |
| 단계 역순 | `phase-regression`: PREPARING→MEASURING 뒤 PREPARING 회귀 거절 | `WorkerProtocolTest`의 phase/sequence 순서 검증 |
| exit 0, RESULT 없음 | `missing-result`: HELLO와 세 PHASE 뒤 정상 exit, 양쪽 executor 모두 실패·재개방 후 미완료 | `missing`은 HELLO조차 없는 빈 출력이며 별도 사례 |
| RESULT 이후 nonzero exit | `nonzero`: 유효한 성공 RESULT 뒤 exit 7, 양쪽 executor에서 실패 | EOF·exit까지 검증해야 하며 RESULT 수신만으로 seal 금지 |
| 중앙 DB 쓰기 실패 | LOCAL_JVM `metricFlushFailureCannotSealEvenAfterWorkerResult`, CONTAINER `databaseWriteFailureCannotSealContainerEvidenceAcrossReopen`: 실제 H2의 appendMetrics 경계에 예외 주입, write 호출 확인·저장 실패·seal 금지 | 실제 DB 서버 중단/디스크 고갈이 아니라 저장 어댑터 오류 주입. 컨테이너 테스트는 DB 재개방과 경계 제거도 검사 |
| RESULT 뒤 최종 flush 실패 | `WorkerProcessTest.storageFlushFailureLeavesPartialEvidenceAndStopsChild`, 컨테이너 `flushFailureAfterResultStillRemovesContainerWithoutReturningSuccess` | RESULT가 전달된 뒤 flush 호출 실패를 명시적으로 확인; transport 성공 반환 금지 |
| 강제 종료 / 중단 | `WorkerProcessTest`의 stubborn/deadline·부모 EOF, 컨테이너 interrupt; `SequenceContainerIntegrationTest.killedControllerRecoversSameStoreAndAdmitsNextRunWithoutSealingPartialEvidence` | 실제 controller SIGKILL·동일 저장소 재시작·부분 증거 불변·후속 접수; I1/I2 실제 PID 경계 회귀도 별도 task |
| pipe 포화 | `WorkerProcessTest.stderrIsDrainedBeyondRetentionWithoutDeadlock`, 컨테이너 `successfulEvidenceSurvivesReopenOnlyAfterContainerRemoval(stderr)`: stderr 2 MiB 출력, 보존 1 KiB, 교착 없이 완료 | stdout 레코드/총량 상한은 별도 protocol 검증. 임의 부하에서 throughput/RSS를 보장하는 성능 검증은 아님 |
| Docker 불가 | `LocalJvmRegistrationTest.unavailableWorkerRejectsBeforeCreatingRun` 및 실제 Java probe `boundedProbeRejectsUnavailableOversizeAndUnresponsiveWorker` | 실제 공유 daemon을 끄지 않음. 준비 불가 응답·무응답 주입을 구분 |
| Docker 복구 통신 장애 | `SequenceRecoveryTransportTest`의 503·무응답·삭제 응답 유실·엔진 변경; `ContainerStartupRecoveryTest`, `SequenceResourceRecoveryTest` | 실제 배포 복구 프로세스+루프백 Docker API 대역, v2 엔진 대역. 실제 전용 daemon 재시작은 별도 승인 실행 기록 |

## 이번 대조에서 보강한 부분

순번 누락·phase 역순은 codec 검증만 있었으므로 실행 fixture에 주입 모드를 추가했다.
`missing-result`는 HELLO·PHASE가 이미 저장된 정상 exit 사례로 빈 stdout과 구분한다.
루트 테스트는 실제 Java 21 자식과 H2를 연결하고 재개방 후 실패·미완료·identity 유무를
검사한다. 컨테이너 suite에는 oversize·빈 출력·RESULT 누락·순번/phase 오류, DB 쓰기 실패와
RESULT 이후 flush 실패를 추가했다. 기존 실패 사례에도 저장소 재개방 검사를 적용했다.
실행 코드의 오류 처리나 제한 시간은 변경하지 않는다.

## 재현 명령

두 Gradle wrapper는 같은 worker-support 산출물을 쓰므로 순차 실행한다.
Docker와 캐시된 Java 21 시험 base 이미지가 필요하다.

```bash
./scripts/career-lab.sh test
./gradlew test containerWorkerTest
```

루트 `test`에 `LocalJvmExecutorTest`가 포함된다. `containerWorkerTest`는 일반 test와 별도이며
로컬 이미지 조립 후 실제 비특권 컨테이너를 사용한다. 실제 sequence API/재시작 회귀는
`careerSequenceTest sequenceContainerTest`, PID 격리는 `workerContainerIsolationTest`다.
이 표의 점검만으로 D5의 나머지 Career 시나리오나 D6의 관측/quota 검증이 완료되지는 않는다.

## 검증 기록

Career 첫 전체 실행에서는 기존 `SequenceWorkerIntegrationTest`의 ATOMIC_UPDATE 한 건이
MySQL 초기화 지연으로 실패했다. 시작 제한 이후 Testcontainers 재시도에서 동일 이름 충돌이
보고됐다. 첫 mcp-platform 44개 결과는 `build/verification/d4/career-first/`에 보존했다.
해당 실패 Run의 잔존 컨테이너가 없음을 확인한 뒤 다음 명령으로 배포 3개와 미실행 모듈을
검증했고 모두 통과했다.

```bash
./scripts/career-lab.sh :mcp-platform:test --tests '*SequenceWorkerIntegrationTest' :worker-support:test :mds-distribution:test --continue
```

worker-support 55개, mds-distribution 8개와 재실행한 배포 3개가 통과했다. 첫 실행의 나머지
mcp-platform 41개 및 변경 없는 batch-excel 2개·creator-studio 10개·equity-system 6개 성공
결과를 합쳐 Career 125개를 확인했다. 첫 전체 명령이 단번에 통과한 것으로 기록하지 않는다.
시간 제한 완화·테스트 비활성화·공유 daemon 재시작은 하지 않았다.

최종 `./gradlew test containerWorkerTest`는 루트 295개와 실제 컨테이너 17개 모두
실패·오류·건너뜀 0으로 통과했다. 이번 추가는 worker-support 3개, 루트 6개, 컨테이너 7개다.
최종 Docker 조회에서 시험 worker·이미지 생성용 컨테이너·임시 이미지 태그가 없었고,
기존 다른 세션의 MySQL은 보존했다. D4 실패 주입 항목의 누락 통합 검증을 완료한다.
