# 전략·사례·시나리오 등록 계약 (P3)

P3는 현재 구현의 설명과 재현 정의를 제공하는 읽기 전용 확장이다. 기존 실행 API,
`ScenarioDefinition` v1, canonical JSON/hash, RunStore, worker protocol은 변경하지 않는다.
[패턴 설계](pattern-platform-design.md)와 [사용 가이드](standalone-dashboard.md)를 함께 참고한다.

## 모델과 소유권

`StudyContribution` Spring bean으로 등록한다. 하나의 `Study`는 안정된 `experimentId`와
그 실험의 사례 맥락(`CaseStudy`), 구현 전략 목록(`Strategy`), 시나리오 프리셋 목록(`Preset`)을 소유한다.
사례는 이 단계에서 실험에 내장된 단일 맥락이며 독립적인 전역 사례 ID는 만들지 않는다.
여러 실험이 같은 프로젝트를 다루면 동일한 근거 문서를 참조할 수 있다.

| 객체 | 필드와 의미 |
| --- | --- |
| Study | experimentId, caseStudy, strategies, presets |
| CaseStudy | title, sourcePath(저장소 상대 문서 경로), provenance(사실·신규 설계 구분), limitations |
| Strategy | id(실험 내부 식별자), title, implementation(구체적 구현과 기존 옵션의 관계) |
| Preset | id(실험 내부 식별자), strategyIds(한 개 이상), sourcePath(기존 JSON/YAML의 classpath 경로) |
| PatternPolicy | patternId, semanticVersion, status(ACTIVE/DEPRECATED), replacementPatternId(null 허용) |

`BEFORE/AFTER`는 도메인별 실행 옵션이지 전역 전략 이름이 아니다. 예를 들어 삭제 실험의
AFTER는 인덱스와 청크 삭제를 함께 적용하고, 배치 실험의 AFTER는 Chunk+flush/clear다.
INVALID_ROW, CONCURRENT, FAIL_AFTER_MEASUREMENT는 입력/경쟁/실패 조건이며 별도 전략을 만들지 않는다.
IO 비교처럼 한 번 실행에서 여러 구현을 비교하는 프리셋은 여러 strategyIds를 참조한다.
현재 IO 실험은 virtual-thread sleep과 Reactor delay 비교다. 별도 IO 도구의 실제 selector 서버와 구분한다.

초기 등록은 16개 실험과 기존 시나리오 40개다. 사례는 저장소 모듈 README를 근거로 설명하며
그 문서가 인용하는 외부 career-hub 문서를 새로 검증했다고 주장하지 않는다. 특히 Creator mapping의
근본 원인은 미확정 가설이고 session 개선은 설계 재구현이다. 합성 수치를 과거 업무 성과로 표시하지 않는다.

## 읽기 API와 프리셋

`GET /dashboard/v1/study-catalog`:

- `schemaVersion: 1`
- `patterns`: 현재 패턴 사전 전체의 의미 버전·폐기 정보. 정책 생략 시 1.0.0/ACTIVE/null.
- `studies`: 현재 `/experiments`에 등록된 실험의 설명만 포함. 설명 미등록 실험은 실행과 탐색을 계속 지원한다.
- 각 preset 응답: id, strategyIds, sourcePath, **definitionJson**, **definitionSha256**.
  정의는 기존 parser의 canonical JSON이며 해시는 기존 `ScenarioDocuments.hash`와 같다.

선언 파일은 `processResources`에서 원본 그대로 classpath `experiments/` 아래에 포함한다.
서버는 HTTP로 받은 경로를 열지 않는다. 기여자가 지정한 신뢰된 classpath 리소스만 시작 시 읽는다.
Git checkout 없이 배포 jar에서도 조회할 수 있다. 원본 파일과 별도의 수동 JSON 복제본을 관리하지 않는다.
프리셋을 저장해 기존 CLI 또는 `POST /runs`로 제출할 수 있다. XLSX 프리셋에는 기존 input 경로/hash가
남으며 실제 파일은 별도로 준비해 multipart로 전달해야 한다. XLSX bytes를 응답에 넣지 않는다.

대시보드는 카드의 **전략·사례·시나리오**에서 설명과 원본 경로를 보여주고 **정의 JSON 저장**을 제공한다.
다운로드는 실행하거나 기존 폼/저장 프리셋을 덮어쓰지 않는다. 자동 적용·Strategy 선택에 따른 실행 변환은
이 계약에 포함하지 않는다. 사용자는 저장한 정의의 조건·입력·실패 주입 여부를 확인한 뒤 제출한다.

## 등록 예시

```java
@Bean
StudyContribution exampleStudies() {
    return new StudyContribution(List.of(new Study(
        "example.queue-pressure",
        new CaseStudy("Queue pressure", "docs/example.md", "NEW-DESIGN 합성 실험", "해당 부하 조건에 한정"),
        List.of(new Strategy("bounded", "용량 제한 큐", "용량 제한으로 생산자 대기를 전달한다")),
        List.of(new Preset("slow-consumer", List.of("bounded"), "experiments/example/slow-consumer.json"))
    )), List.of());
}
```

정적 import는 `StudyContribution.*`다. provider와 실행 등록은 별도로 필요하다. 추가 jar는
해당 resource를 classpath에 포함해야 한다. 같은 experiment의 Study를 다른 bean으로 덮어쓸 수 없다.
새 설명을 추가할 때 controller/frontend 분기를 수정하지 않는다.

## 검증과 상한

- contributions ≤256, 전체 Study ≤256, 전체 패턴 정책 ≤1024.
- 실험당 전략 ≤32(최소 1), 프리셋 ≤64, 프리셋당 전략 참조 1–32개.
- ID: 소문자 영문 시작, 소문자·숫자·점·하이픈, 최대 100자. 텍스트 ≤2000자, 경로 ≤300자.
- 상대 경로만 허용하고 빈 segment·`.`·`..`·URL·절대 경로를 거부한다.
- 문서당 ≤64 KiB, 전체 프리셋 원문 합계 ≤4 MiB. 목록과 응답은 불변 snapshot이다.
- 중복 실험/전략/프리셋/정책, 없는 전략·패턴·대체 패턴, 대체 순환, 누락 resource,
  문서 experiment 불일치와 잘못된 시나리오 문법은 시작 시 실패한다.
- 비활성 실험의 문법·내부 참조도 검증한다. 활성 실험은 실제 descriptor 파라미터 schema까지 검증한다.
  전체 provider 테스트에서 비활성 Career를 포함한 16개 schema와 40개 정의를 검증한다.
- CaseStudy.sourcePath는 근거 표시용이다. 서버가 문서를 읽거나 링크 실행하지 않는다.
  내장 근거 경로의 존재는 저장소 테스트에서 확인한다.

## 패턴 의미 버전과 폐기

응답 `schemaVersion`과 패턴 `semanticVersion`은 별개다. 의미를 유지하는 설명 정정은 patch,
기존 의미를 유지하는 추가 설명/적용 사례는 minor로 기록할 수 있다. 검증 의미가 달라지면 새 pattern ID를
등록한다. 이전 ID는 사전에 남기고 DEPRECATED와 대체 후보를 지정한다. 대체 후보는 반드시 존재해야 하며
순환할 수 없다. 같은 ID를 다른 의미로 재사용하거나 alias로 자동 치환하지 않는다.

이전 ID의 의미를 바꾼 major bump만으로 호환성을 선언하지 않는다. 기존 ID를 유지한 의미 변경을
기계적으로 판별할 수 없으므로 변경 리뷰에서 정책을 지켜야 한다. 현재 등록 정책은 모두 1.0.0/ACTIVE다.
과거 Run에는 정책/전략을 소급 기록하지 않으며 현재 설명을 실행 당시 증거로 취급하지 않는다.

## 구형 서버와 검증 범위

study API는 P1 pattern API와 독립적이다. 404/지원하지 않는 버전은 기존 탐색을 유지한다.
5xx·네트워크·손상된 v1 응답은 사례 오류와 재시도로 표시하며 실행·기록 접근을 막지 않는다.
`StudyCatalogTest`는 모든 원본 정의의 canonical/hash와 schema, 확장 bean, 참조·상한·폐기 정책·HTTP를
검증한다. `ScenarioApiIntegrationTest`는 설명 API 조회 후 기존 Run/hash 보존을 확인한다.
프런트엔드 `studies.test.ts`, `tests/studies.spec.ts`는 계약 거부·다운로드·오류 복구·모바일을 검증한다.
실제 Career worker의 재실행이나 운영 배포 전환은 이 단계에 포함하지 않는다.
