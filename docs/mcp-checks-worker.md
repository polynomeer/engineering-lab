# MCP architecture / validation worker (D5-1)

## 범위와 해석

두 실험은 Java 21의 DB 없는 `checks-worker` 배포물에서 실행한다.
기존 sequence의 LOCAL_JVM/CONTAINER 설정·manifest는 그대로 사용한다.
checks만 켜거나 sequence와 함께 켤 수 있으며 `/modules`에는 하나의
`mcp-platform` 모듈과 활성화한 실험만 나온다.

| 실험 | variant | 기대 결과 |
| --- | --- | --- |
| `career.mcp-platform.architecture` | `COMPLIANT` | 실제 contract 클래스의 세 ArchUnit 규칙 PASS |
| 위와 같음 | `VIOLATION` | 합성 badexample 클래스: import PASS, 세 규칙 FAIL, Run FAILED |
| `career.mcp-platform.validation` | `VALID` | Bean Validation, 필드 전달, 도메인 규칙 모두 PASS |
| 위와 같음 | `BLANK_NAME`, `PAST_EXPIRY` | 형식 오류 탐지 PASS, 후속 두 단계는 `not executed` |
| 위와 같음 | `DOMAIN_REJECTION` | 형식/매핑 통과 후 실제 도메인의 날짜 역전 거절 PASS |

PASS는 **expected와 observed의 일치**다. 입력이 거절된 후의 PASS는 후속 단계를
실행하지 않았다는 제어 흐름 확인이며, 매핑/도메인 로직 자체를 실행해 통과했다는
뜻이 아니다. worker v1의 PASS/FAIL 계약을 유지한다. architecture의 VIOLATION은
실제로 규칙을 어긴 결과를 FAIL로 기록한다. 실패 Run은 증거 완료로 봉인하지 않아도
이미 기록된 check와 REPORT 참조를 조회/export할 수 있다.

**FACT 기반 재현:** 기존 레이어 규칙, Bean Validation → DTO 필드 전달 → 도메인
규칙의 경계. **NEW-DESIGN:** worker, 합성 위반 클래스/입력, 날짜와 코드 값,
고정 시계 `2026-01-01T00:00:00Z`, 결과 저장/화면 연동. DTO의 필드 전달은
서비스의 수동 전달 방식을 DB 없이 재현하며 MapStruct·HTTP binding·DB 통합
검증이나 실제 업무 성능 측정으로 해석하지 않는다.

`checkRuntime` source set에 ArchUnit 규칙과 위반 fixture를 두어 JUnit과 같은
규칙을 재사용한다. 일반 Boot 앱과 sequence jar에는 포함되지 않는다.
worker는 Spring context, DB, Docker client를 시작하지 않는다. 고정된 대상만
import하며 빈 import는 실패한다. 체크 수는 architecture 4개, validation 3개이며
규칙 상세가 8000자를 넘으면 잘라서 성공시키지 않고 실패한다.

## 빌드와 설정

```bash
./scripts/career-lab.sh test :mcp-platform:checksWorkerManifest
```

`career-lab/mcp-platform/build/libs/checks-worker-manifest.json`은 jar 이름,
SHA-256, protocol 1, Java 21, 두 experiment ID를 제공한다. manifest의
`requiresDocker=false`는 worker 내부에 Docker 의존성이 없다는 뜻이다.
중앙 실행 방식은 **CONTAINER만 지원**하므로 controller의 Docker는 필요하다.

[컨테이너 이미지 계약](worker-container-isolation.md#i3-protocol-transport와-중앙-증거-연결)에
맞게 Java 21 JRE 이미지의 `/opt/lab/worker.jar`에 checks jar를 넣고 이미지를 만든다.
서버 설정에는 태그가 아닌 `docker image inspect ... --format '{{.Id}}'`의 전체
image ID와 manifest의 jar SHA-256을 사용한다. 서버는 jar hash를 HELLO와 대조한다.

```yaml
lab:
  career:
    checks:
      enabled: true                  # 기본 false
      recover-on-startup: false       # 기본 false; 명시적으로 켜면 v2 기록 조정
      container:
        image-id: sha256:<64자리 이미지 digest>
        artifact-sha256: <64자리 jar SHA-256>
```

기본값의 빈 image/hash는 활성화 전에 채워야 한다. 준비 실패는 새 접수를 차단한다.
architecture/validation은 각각 독립 등록/접수 guard를 사용한다. 네트워크·DB·host
mount 없이 공통 격리 프로필로 실행하고 종료 후 해당 컨테이너를 제거한다.
부모 연결 상실/deadline은 worker 프로세스를 종료한다. controller 사망 후에는
기존 v2 컨테이너 복구 규칙을 따르며 미확인 create/start를 자동 해제하지 않는다.

## 실행과 조회

대시보드 `/app/experiments/career.mcp-platform.architecture` 또는
`/app/experiments/career.mcp-platform.validation`에서 preset을 선택한다.
시나리오 JSON은 [samples](../experiments/career/mcp-platform/scenarios/)에 있다.

```bash
./gradlew lab --args='run experiments/career/mcp-platform/scenarios/architecture-compliant.json --wait'
./gradlew lab --args='run experiments/career/mcp-platform/scenarios/validation-domain-rejection.json --wait'
```

Run 상세에서 체크 ID/기대값/관측값, worker Java/OS/hash, 증거 완료 상태를 조회한다.
이 실험은 METRIC을 생성하지 않아 catalog의 presentation에도 METRICS가 없으며
빈 관측 표 대신 검증 결과와 내보내기 안내를 제공한다. 원시 체크와 실행 정보는
확장 NDJSON export로 받는다. `checks-report.json`은 hash/크기를 검증해 중앙
저장소에 보관하며 export에는 참조를 제공하며 화면에서는 관리 사본을 다운로드한다. D6에서는 검증된 중앙 REPORT 사본 다운로드도 지원한다.
서버 재시작 후에도 같은 저장소에서 결과를 조회한다.

## 검증

두 wrapper는 공유 산출물을 사용하므로 **순차 실행**한다.

```bash
./scripts/career-lab.sh test :mcp-platform:checksWorkerManifest :mcp-platform:sequenceWorkerManifest
./gradlew test checksContainerTest
```

`ChecksWorkerIntegrationTest`는 실제 배포 jar의 여섯 variant를 128 MiB JVM에서
검증한다. `checksContainerTest`는 캐시된 Java 21 이미지를 복사/commit으로
구성하여 실제 컨테이너, schema 거절, API 제출, 성공/실패 check, REPORT,
export, 저장소 재개방, 자원 제거를 검증한다. 테스트 image는 끝에 제거한다.
성공한 실제 API 응답/보고서 스냅샷은 `build/verification/d5-1/`에 보관한다.
frontend `mcp-checks.spec.ts`는 이 스냅샷으로 체크 표시·빈 메트릭 안내를 검증한다.
브라우저 검증은 기록된 실제 응답을 사용하며 브라우저에서 Docker를 직접 실행하는
검증과 구분한다.

브라우저 스냅샷 검증은 위 컨테이너 검증이 성공한 뒤 수행한다(Node 버전은
[frontend README](../frontend/README.md)를 따른다). 별도 터미널에서
`cd frontend && npm run dev`로 화면을 띄우고, frontend 디렉터리에서 실행한다.

```bash
MCP_CHECKS_EVIDENCE=../build/verification/d5-1 npm run test:e2e -- mcp-checks.spec.ts
```

실제 응답 스냅샷 6개와 preset 제출 2개를 검사한다. 스냅샷 테스트는 환경변수가
없으면 명시적으로 건너뛰며, 지정한 디렉터리에 파일이 없으면 실패한다.

최종 검증 기록 (2026-10-15): Career 131건, 루트 297건, checks 컨테이너 6건,
기존 sequence API 3 variants를 포함한 회귀 1건, frontend 단위 17건·브라우저 12건이
실패/건너뜀 없이 통과했다(Career의 미변경 모듈은 기존 성공 결과 일부 재사용).
타입 검사와 빌드도 통과했고 테스트 worker 컨테이너·이미지 및 Vite 서버를 정리했다.

D6의 현재 동작·제한·운영 명령은 [통합 운영 가이드](dashboard-operations.md)를 따른다.
