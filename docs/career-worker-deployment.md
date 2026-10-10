# Career sequence 중앙 API 연결

첫 Career 실험 `career.mcp-platform.sequence`를 별도 Java 21 worker로 실행하고,
Java 25 플랫폼의 기존 Run 저장·조회·비교 API에 결과를 보관한다. 기본값은 비활성이다.
독립 대시보드의 실행·검증 결과 화면을 지원한다. [화면 사용법](standalone-dashboard.md#career-sequence-실행과-검증-결과)을 참고한다. 오프라인 CLI의 Career 검증은 아직 연결하지 않았다.
기존 `/runs/ui`와 Run 조회 API에서 접수된 실행을 조회할 수 있다.

## 빌드와 검증

Java 25/21과 실행 중인 Docker가 필요하다. 두 wrapper는 worker-support 산출물을 공유하므로
명령을 순서대로 실행한다. 실제 worker 빌드는 중앙 앱 빌드에 자동 포함되지 않는다.

```bash
./scripts/career-lab.sh test :mcp-platform:sequenceWorkerManifest
./gradlew test
./gradlew careerSequenceTest
```

마지막 명령은 별도 `src/careerTest` 통합 검증이다. 실제 Spring 등록과 HTTP 접수 경로로
세 variant를 실행하고, worker/controller 환경 분리·CHECK·보고서·비교 단위와 DB 재조회를
확인한다. 실제 DB 배포 테스트는 느린 MySQL 초기화를 포함해 실행당 최대 5분을 허용한다. 동시 채번 자체의 30초 제한과 중단 후 정리 제한은 바꾸지 않는다. 기본 `test`는 Docker 없이 등록·거절·probe와 기존 플랫폼을 검증한다.

## 서버 설정

서버 관리자가 신뢰하는 빌드만 등록한다. 생성된 descriptor와 jar를 같은 디렉터리에 둔다.
아래 Java 경로를 설치된 JDK 21 실행 파일의 **절대 경로**로 바꾼다.

```bash
LAB_CAREER_SEQUENCE_ENABLED=true \
LAB_CAREER_SEQUENCE_MANIFEST="$PWD/career-lab/mcp-platform/build/libs/sequence-worker-manifest.json" \
LAB_CAREER_SEQUENCE_JAVA_EXECUTABLE="/absolute/jdk21/bin/java" \
./gradlew bootRun
```

| 설정 | 기본값 / 계약 |
| --- | --- |
| `lab.career.sequence.enabled` | `false`; true일 때만 모듈·실험·실행기를 등록 |
| `lab.career.sequence.manifest` | 빈 문자열; 활성화 시 관리자 소유 descriptor 파일 필요 |
| `lab.career.sequence.java-executable` | 빈 문자열; 활성화 시 Java 21 절대 경로 필요 |

descriptor는 16 KiB 이하이며 schema/protocol 1, Java 21, mcp-platform, 실험 ID,
heap 256 MiB, Docker 요구와 단일 jar 파일명·SHA-256을 검증한다. descriptor 누락/잘못된 형식은
서버 시작 실패다. 파일명은 같은 디렉터리의 jar만 허용한다. HTTP 입력으로 실행 파일·jar·heap·명령을
변경할 수 없다. 교체 배포는 worker와 descriptor를 함께 배포하고 서버를 재시작한다.

## 준비 상태와 접수

`GET /dashboard/v1/modules`와 `/dashboard/v1/experiments/career.mcp-platform.sequence`가
준비 상태를 제공한다. `READY`, `NOT_BUILT`(jar 없음), `INCOMPATIBLE`(hash/Java/protocol 오류),
`DOCKER_UNAVAILABLE`을 구분한다. 모듈 응답의 reason은 경로·인증정보가 없는 진단 코드다.

probe는 검증한 jar 사본을 동일 Java/Docker 환경에서 `--probe`로 실행한다. stdout은 최대
4 KiB, 프로세스 응답 대기는 최대 60초, 결과 캐시는 5초다. 최초 조회/접수는 Docker 준비 때문에
지연될 수 있다. probe 종료/입출력 정리가 확인되지 않으면 추가 probe를 중단하고 `PROBE_CLEANUP_UNCONFIRMED`를 보고한다. 관리자가 원인을 확인하고 앱을 재시작해야 한다. probe는 MySQL 실험 DB를 만들지 않지만 Testcontainers가 Ryuk을 시작할 수 있다.
`TESTCONTAINERS_RYUK_DISABLED=true`이면 준비되지 않은 상태로 보고한다.

준비되지 않은 worker의 새 접수는 HTTP 503이며 Run을 만들지 않는다. 준비 상태는 시점 관측이므로
접수 직후 Docker가 중단되면 기록된 Run은 FAILED가 될 수 있다. 실제 실행 때 jar hash를 다시
검증한다. worker는 배타 실행으로 한 번에 하나만 허용하며, 다른 실행과 충돌하면 접수된 Run을
기존 admission 정책에 따라 실패 처리한다. 무제한 대기 큐를 추가하지 않는다.

## 실행과 결과

```bash
curl -H 'Content-Type: application/json' \
  --data-binary @experiments/career-sequence/scenarios/atomic-update.json \
  http://localhost:8080/runs
```

variant는 `BEFORE`, `ATOMIC_UPDATE`, `SELECT_FOR_UPDATE`, workers는 2–16,
blockSize는 1–10,000이다. 응답의 runId로 다음 API를 조회한다.

- `/runs/{runId}`: 상태·요약·서버 환경
- `/dashboard/v1/runs/{runId}/execution`: 실제 Java 21 worker 환경·배포 hash·증거 완료 상태
- `/dashboard/v1/runs/{runId}/checks`: 겹침·구간 검증
- `/runs/{runId}/metrics`, `/runs/{runId}/artifacts`: 메트릭과 검증된 중앙 보고서 참조
- `/runs/compare?baseline={beforeId}&candidate={afterId}`: 측정 조건·실행 환경·값 비교

sequence 메트릭은 모듈이 단위와 의미를 선언하며 `/metrics/schema`와 비교 API에 반영한다.
BEFORE의 경합 재현용 50ms 지연 때문에 elapsedNanos를 업무 성능 개선율로 해석하지 않는다.
worker의 JFR/trace/일반 로그/JVM 관측 capability는 false다. controller 관측을 worker 측정값으로
대신 표시하지 않는다. 보고서 다운로드 화면, 저장 용량 quota, 작업 폴더 자동 정리는 후속 범위다.

## 복구 범위

정상 정리와 부모 연결 종료/deadline/강제 종료의 MySQL 회수는
[worker 장애 처리](../career-lab/mcp-platform/README.md#중단과-장애-처리)를 따른다.
등록 worker는 실행 전 영속 기록을 만들고 성공·정리 확인 후 제거한다. 실행 중이거나 실패·강제 종료 후 기록이 남으면 `INCOMPATIBLE` / `WORKER_ACTIVE_OR_RECOVERY_REQUIRED`로 새 접수를 차단한다. [복구 기록과 운영자 해제 절차](worker-recovery.md)를 따른다. Docker/Ryuk 자체 장애, 호스트 재시작 이후 잔여물 자동 조정과 일반 자손 프로세스 정리는 남아 있다.
Run 저장소의 기존 재시작 복구는 중단된 Run을 실패로 처리하며 성공으로 추정하지 않는다.
EOF/exit/증거 저장이 모두 확인되어야 성공 증거가 완료된다. 과거 Run 조회에는 worker 실행이 필요 없다.
