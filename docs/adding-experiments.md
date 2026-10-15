# 실험과 Career 모듈 추가하기

기존 실험과 새 실험은 공통 catalog와 Run API를 사용한다. 이 문서는 **현재 구현된 등록 경계**와 **후속 worker/UI 연동**을 구분한다.

## 공통 개념

| 개념 | 역할 | 예 |
| --- | --- | --- |
| collection | 모듈을 분류하는 표시용 그룹, 실행 방식과 독립 | engineering, career |
| module | 함께 관리하는 실험 묶음 | pipeline, concurrency, io |
| experiment | 안정된 ID·입력 schema·실행 코드 | pipeline.excel-ingestion |
| scenario | 특정 입력·가설·파라미터 조합 | small-batch.yaml |
| run | 한 번 실행한 결과와 영속 증거 | UUID |
| executor | 실행 환경; 그룹과 독립 | IN_PROCESS, LOCAL_JVM, CONTAINER |

Career라는 이름으로 별도 대시보드 구현을 요구하지 않는다. 앞으로 engineering 실험도 다른 JDK/외부 인프라가 필요하면 worker 실행을 선택할 수 있도록 설계한다. 현재 sequence는 서버 설정으로 LOCAL_JVM 또는 CONTAINER worker를 등록할 수 있다. architecture/validation은 [전용 checks CONTAINER worker](mcp-checks-worker.md)로 등록한다. equity-system의 세 실험은 [equity CONTAINER worker](equity-worker.md)와 실행별 MySQL/Redis 자원을 사용한다. 나머지 Career 시나리오에는 별도의 driver와 등록이 필요하다.

## 현재 구현: 내장 실험 추가

1. `com.pnomeer.lab.experiments.<topic>`에 `Experiment<C>`와 `ExperimentScenario`를 구현한다. 실행 중 metric은 전달받은 sink에 보내고 기존 bounded writer/Run 저장소를 재사용한다.
2. 같은 패키지에 `ExperimentModule` 구현을 추가한다. 인자 없는 public 생성자, `module()`의 ID/제목/collection, `experiments()`의 파라미터·입력 descriptor를 정의한다. Spring/DB 초기화를 하는 생성자를 만들지 않는다. 하나의 모듈이 여러 실험을 제공할 수 있다.
3. `src/main/resources/META-INF/services/com.pnomeer.lab.registry.ExperimentModule`에 provider의 전체 클래스명을 등록한다. 외부의 신뢰된 빌드 jar는 자체 service 파일을 포함할 수 있다. 런타임 사용자 업로드나 임의 클래스 실행은 지원하지 않는다.
4. 모듈 패키지의 Spring `@Configuration`에서 실험별 `ExperimentRegistration<C>` bean을 제공한다. descriptor는 2번 정의를 그대로 참조한다. configuration은 `com.pnomeer` 스캔 범위에 두거나 애플리케이션에서 명시적으로 import한다.
5. module의 `metrics()`에 새 metric의 이름·단위·scope·semantics·aggregation을 정의한다. 기존 metric 이름은 덮어쓰지 않는다. 이름은 `<module>.<meaning>`처럼 충돌을 피하고 알 수 없는 metric의 단위는 추정하지 않는다.
6. `experiments/<topic>/<experiment>/scenarios/`에 JSON/YAML 예제와 README를 추가한다. 범위·기본값·입력 checksum·가설을 명시한다.
7. 실제 binding/실행·오류/경계·기존 API 호환을 검증하고 `./gradlew test` 후 작업 단위로 커밋한다.

참조 구현:

- [QueueExperimentModule](../src/main/java/com/pnomeer/lab/experiments/concurrency/QueueExperimentModule.java) / [QueueRegistrationConfiguration](../src/main/java/com/pnomeer/lab/experiments/concurrency/QueueRegistrationConfiguration.java)
- [IoExperimentModule](../src/main/java/com/pnomeer/lab/experiments/io/IoExperimentModule.java) / [IoRegistrationConfiguration](../src/main/java/com/pnomeer/lab/experiments/io/IoRegistrationConfiguration.java)
- [PipelineExperimentModule](../src/main/java/com/pnomeer/lab/experiments/pipeline/PipelineExperimentModule.java) / [PipelineRegistrationConfiguration](../src/main/java/com/pnomeer/lab/experiments/pipeline/PipelineRegistrationConfiguration.java)

`RegistryConfiguration`과 CLI에 실험별 분기를 추가하지 않는다. `ExperimentDescriptors`는 기존 참조를 위한 alias이며 새 상수를 추가하는 곳이 아니다. 기존 metric의 의미는 `MetricCatalog`에 유지하고 신규 metric만 모듈에 정의한다.

### 등록과 호환 보장

`ExperimentCatalog`는 Java ServiceLoader로 metadata provider를 수집하고 module/experiment ID와 metric 중복을 검사한다. Spring은 `ExperimentRegistration<?>` bean들을 수집한다. 서버 시작 시 catalog와 실행 registration의 ID 집합·descriptor 일치 여부를 확인해 누락이나 다른 schema로 실행되는 것을 차단한다.

`GET /modules`는 `[{module:{id,title,collection},experiments:[...]}]`를 반환한다. 현재 등록된 내장 모듈과 활성화한 MCP 시나리오를 포함하며 아직 worker 등록이 없는 Career 시나리오를 실행 가능한 것으로 표시하지 않는다. 기존 `/experiments` 응답 형태와 세 실험 ID는 유지한다. 기존 `/runs/ui`는 catalog의 새 실험과 공통 Run summary를 조회할 수 있다.

CLI `validate`/`run`의 로컬 검증도 같은 provider를 사용한다. CLI 배포물에 없는 실험은 unknown으로 거부하므로 서버와 같은 module metadata를 포함한 배포물을 사용한다. 런타임에 임의 서버의 schema를 받아 신뢰하는 기능은 아직 없다.

새 실험은 `POST /runs`와 CLI로 실행할 수 있다. 기존 `/experiments/ui`의 큐/IO 전용 폼은 호환 화면이며 자동 폼 생성 UI가 아니다. 독립 대시보드 `/app/experiments`는 input=NONE, measurementModes에 SINGLE_PASS, 정수/boolean/선택형 string 파라미터를 가진 새 실험의 폼을 자동 생성한다. 파일 업로드와 도메인 결과 패널은 [독립 대시보드 설계](dashboard-integration-design.md)의 후속 구현이다.

현재 input 종류는 NONE / XLSX_UPLOAD, 측정 방식은 SINGLE_PASS다. 새 파일 형식·측정 방식·executor를 추가할 때는 업로드 상한·검증·수명·CLI·화면 계약도 함께 확장한다. 등록 provider 하나로 모든 새로운 실행 환경을 자동 지원하는 것은 아니다.

## 확장 시 지켜야 할 경계

- module ID와 experiment ID는 영속 Run의 식별자다. 제목 변경은 가능하지만 ID를 이름 정리 목적으로 바꾸지 않는다.
- 실행 종료·retry·dead-letter·메모리 제한은 공통 runtime을 우회하지 않는다.
- collection 값으로 실행기를 선택하지 않는다. career와 engineering은 사용자 탐색 분류이며 JVM/프로세스 요구는 별도 capability다.
- 새 기능에는 공통 표/원시 데이터 fallback을 제공한다. 모듈 이름별 if/switch를 dashboard와 controller에 늘리지 않는다.
- 공통 DTO의 삭제/필수 필드 변경은 기존 저장 데이터·CLI·UI 호환 검증 없이 수행하지 않는다.
- schema provider는 신뢰된 빌드 코드다. 프로세스 실행·파일 접근·DB 연결 같은 부작용을 두지 않는다.

## 현재 구현: Career 빌드 모듈 추가

`career-lab/settings.gradle.kts`는 바로 아래 디렉터리 중 `lab-module.properties`가 있는 모듈을 이름순으로 포함한다. 기존 다섯 모듈도 같은 규칙을 사용한다. 새 모듈 이름을 중앙 include 목록에 추가하지 않는다.

예를 들어 `career-lab/payment-settlement/`에 Java 21 / Boot 3.3.4 호환 `build.gradle.kts`, `src/`, README와 다음 선언을 둔다.

```properties
schemaVersion=1
id=payment-settlement
title=Payment settlement
collection=career
```

UTF-8 선언의 필수 필드는 위 네 개뿐이다. ID는 디렉터리명과 같고 소문자로 시작하는 영문/숫자/hyphen 최대 64자다. title은 공백만 허용하지 않으며 최대 200자, collection은 소문자 영문으로 시작하는 영문/숫자/hyphen 최대 50자다. 기존 ID를 유지한다. schemaVersion 불일치·필드 누락/추가·빌드 파일 누락은 실패한다. 선언 파일 없는 참고 자료나 임시 폴더는 자동 제외한다.

```bash
./scripts/career-lab.sh projects
./scripts/career-lab.sh :payment-settlement:test
./scripts/career-lab.sh test
python3 scripts/verify-career-modules.py
./gradlew test
```

위 `payment-settlement`는 설명용이며 저장소에 생성되어 있지 않다. 검증 스크립트는 임시 프로젝트를 만들어 실제 Gradle에서 새 모듈 발견·미선언 폴더 제외·ID/version/build 오류를 확인한 뒤 삭제한다. JDK 선택은 `scripts/career-lab.sh`와 동일하다.

빌드 자동 포함과 공통 대시보드 실행 등록은 다른 단계다. 현재 Career 코드가 자동으로 Run registry에 올라가지는 않는다. worker 연동 시 이 module ID/collection을 유지하고, catalog descriptor와 배포 manifest의 실험 ID·배포물 hash를 일치시킨다. 실험별 driver와 테스트를 제공하면 같은 실행·조회 화면에 연결되게 한다. 지원 라이브러리 모듈은 실험 module과 구분하는 별도 빌드 구성이 필요하다.

## 현재 구현: worker 실행 등록

1. 공통 catalog와 `ExperimentRegistration`에 실험 ID·입력 schema를 등록하고, Java 21 worker에서 [worker v1](../contracts/worker/v1/README.md) protocol을 구현한다.
2. 서버의 모듈 configuration에서 `WorkerRegistration` bean을 제공한다. `LocalJvmRegistration`은 Java 실행 파일과 jar manifest를, `ContainerWorkerRegistration`은 고정 이미지 ID와 jar hash를 사용한다. 실행 방식·이미지·명령은 사용자 scenario 입력으로 받지 않는다.
3. 요청 변환, 허용 metric, variant와 readiness를 등록한다. runner와 dashboard는 공통 등록을 수집하므로 모듈 이름별 분기를 추가하지 않는다.
4. 컨테이너 외부 자원이 필요하면 `ContainerWorkerResources`의 준비·정리와 `profile`/`recovery`를 함께 구현한다. 자원이 없으면 `NONE`을 사용한다. controller가 자원을 소유하고 worker에는 제한된 접속 정보만 전달한다.
5. 정상 실행·부분 실패·실제 프로세스 중단·같은 저장소 재시작·접수 재개를 검증한다. 생성/시작 미확인과 소유권 불일치에서 차단이 유지되어야 한다. 기존 Run 증거와 LOCAL_JVM v1 기록을 새 복구 정책으로 해제하지 않는다.

참조 구현은 `SequenceRegistrationConfiguration`과 `SequenceContainerResources`다.
[배포 가이드](career-worker-deployment.md)와 [컨테이너 복구 계약](worker-container-isolation.md#i5-같은-저장소에서-시작-시-복구와-접수-재개)을 따른다.
등록 추가만으로 다른 모듈의 자원 복구 정책이나 worker 관측 기능이 자동 구현되지는 않는다.

## 확장 완료 판단

새 내장 실험은 module metadata + registration + scenario + 테스트로 추가한다. 새 Career 프로젝트는 디렉터리 + 선언 + 자체 빌드/테스트로 추가한다. 공통 UI/worker 도입 후에는 모듈 이름별 화면·실행 분기 없이 capability와 등록된 panel/executor를 사용해야 한다. 단계별 구현 상태는 [대시보드 이행 계획](dashboard-integration-plan.md)에 기록한다.
