# Career Lab

이력서 기반 프로젝트의 문제·결정·결과를 재현하는 다섯 개의 실습 모듈입니다.
`engineering-lab` 저장소 안에서 독립된 Gradle 다중 프로젝트로 빌드합니다.
각 README의 **FACT 기반 재현**과 **NEW-DESIGN** 구분을 유지하며, 실험 수치를 실제 업무 성과와 혼동하지 않습니다.

| 모듈 | 주제 | 상세 문서 |
| --- | --- | --- |
| `equity-system` | 대량 삭제, Redis 분산락, 병렬 등록 | [사용법](equity-system/README.md) |
| `batch-excel` | 배치 처리와 Excel 메모리 비교 | [사용법](batch-excel/README.md) |
| `mcp-platform` | 계약 코드 채번, 아키텍처 규칙, 검증 | [사용법](mcp-platform/README.md) |
| `mds-distribution` | 정산 동시성, 이벤트 멱등성 | [사용법](mds-distribution/README.md) |
| `creator-studio` | 세션 회전, 매핑 무결성 | [사용법](creator-studio/README.md) |

공통 통신 기반으로 Java 21 [worker-support](worker-support/README.md)를 추가했습니다. 다섯 실험 모듈과 별개의 infrastructure 라이브러리입니다. sequence의 [선택적 중앙 API 연결](../docs/career-worker-deployment.md)을 지원하며 [전용 화면 사용법](../docs/standalone-dashboard.md#career-sequence-실행과-검증-결과)도 제공합니다.

Java 21 [pipeline-support](pipeline-support/README.md)는 Excel/Equity가 공유하는 무의존 배치 primitive입니다.
worker 통신과 별개이며 실험 모듈을 추가하지 않습니다. [공통 계약](../docs/pipeline-contract.md)을 따릅니다.

## 모듈 추가

각 모듈의 `lab-module.properties`를 읽어 빌드에 자동 포함합니다. 새 모듈은 디렉터리·선언·빌드 파일을 추가하며 중앙 목록은 수정하지 않습니다. [확장 가이드](../docs/adding-experiments.md#현재-구현-career-빌드-모듈-추가)를 참고하세요.

## 준비와 실행

JDK 21과 실행 중인 Docker가 필요합니다. Gradle Wrapper는 8.14.2,
Spring Boot는 3.3.4를 유지합니다. 최초 실행에는 Gradle·Maven 의존성과
MySQL/Redis 컨테이너 이미지 다운로드가 필요합니다.

저장소 루트에서 실행합니다. macOS에서는 스크립트가 설치된 JDK 21을 선택합니다.
다른 운영체제에서는 `JAVA_HOME`과 `PATH`에 JDK 21을 설정합니다.
`CAREER_LAB_JAVA_HOME`을 지정하면 운영체제에 관계없이 그 경로를 우선 사용합니다.

```bash
# 다섯 실험 모듈과 worker-support/pipeline-support 전체 테스트
./scripts/career-lab.sh test

# 한 모듈만 테스트
./scripts/career-lab.sh :equity-system:test
./scripts/career-lab.sh :batch-excel:test
./scripts/career-lab.sh :mcp-platform:test
./scripts/career-lab.sh :mds-distribution:test
./scripts/career-lab.sh :creator-studio:test

# 테스트 캐시를 사용하지 않고 다시 실행
./scripts/career-lab.sh clean test
```

스크립트 없이 JDK 21 환경에서 직접 실행할 수도 있습니다.

```bash
./career-lab/gradlew -p career-lab test
```

Windows에서는 JDK 21을 설정하고 `career-lab\gradlew.bat -p career-lab test`를 사용합니다.
테스트 보고서는 `career-lab/<모듈>/build/reports/tests/test/index.html`에 생성됩니다.
테스트가 생성한 컨테이너는 Testcontainers가 관리합니다. Docker 접근 오류가 나면
먼저 Docker 실행 상태와 현재 사용자의 접근 권한을 확인합니다.

## 현재 플랫폼과의 관계

루트의 `./gradlew test`와 `./gradlew bootRun`은 Java 25 / Spring Boot 4 기반
실험 플랫폼용입니다. Career Lab은 별도 wrapper와 settings를 사용하므로
루트 테스트만으로 다섯 모듈이 검증되지는 않습니다. 양쪽 변경 시 두 테스트 명령을 모두 실행합니다.

기본 실행은 각 모듈의 테스트입니다. 설정으로 활성화하면 sequence와 [architecture/validation](../docs/mcp-checks-worker.md)을 중앙 registry에 등록할 수 있습니다. `mcp-platform`의 sequence에는 실제 Java 21 worker 배포 jar와 격리 MySQL 통합 검증을 추가했습니다. [worker 빌드·계약](mcp-platform/README.md#별도-jvm-sequence-worker-d4)을 참고하세요.
모듈 간 소스·의존성을 임의로 합치거나 Boot 버전을 일괄 변경하지 않습니다.
공통 Run UI 연결은 [독립 대시보드·실행 어댑터 설계](../docs/dashboard-integration-design.md)와
[이행 계획](../docs/dashboard-integration-plan.md)에 정의했으며, 공통 기반·첫 worker 배포·선택적 sequence 중앙 API 등록을 구현했습니다. 독립 대시보드의 실행·검증 결과 UI도 연결했습니다.

## 복구 출처

2026-10-04, 이전 원격 main의 `d52297d0ad07c3d8a05a7471192a5655cb8b1378`에서
`career-lab/` 전체를 복구했습니다. Java 소스, 테스트, 리소스, 모듈별 빌드 설정은
해당 커밋 그대로이며, 실행 경로 설명과 이 독립 빌드의 진입점만 추가·조정했습니다.
기존 루트 실험 플랫폼의 최신 구현과 문서는 그대로 유지합니다.

## 복구 후 검증 (2026-10-04)

JDK 21.0.7, Gradle 8.14.2, Docker 28.0.4 환경에서 전체 테스트를 실행했습니다.

| 모듈 | 통과 | 실패 / 건너뜀 |
| --- | ---: | ---: |
| batch-excel | 2 | 0 / 0 |
| creator-studio | 10 | 0 / 0 |
| equity-system | 6 | 0 / 0 |
| mcp-platform | 16 | 0 / 0 |
| mds-distribution | 8 | 0 / 0 |

총 42개 테스트가 통과했습니다. 루트 `./gradlew test`도 성공했고,
기존 134개 테스트 결과를 최신 상태로 재사용했습니다(`UP-TO-DATE`).
이 검증은 복구한 코드의 실행 확인이며, 과거 README에 기록된 성능 수치를
이번 환경에서 동일하게 재현했다는 의미는 아닙니다.
