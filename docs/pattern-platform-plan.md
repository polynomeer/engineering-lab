# 패턴 중심 플랫폼 작업 계획

기준: 2026-10-17. [설계](pattern-platform-design.md)의 계약과 기존 v0.1.0 호환성을 유지한다.
각 단위는 구현·관련 회귀·전체 루트 테스트·diff 검토 후 독립 Conventional Commit으로 완료한다.

| 단계 | 상태 | 작업 및 산출물 | 완료 기준 |
| --- | --- | --- | --- |
| P0 | 완료 | 개념·16개 실험 지도·호환/측정/배포 원칙 문서화 | 설계와 단계별 검증 기준 연결 |
| P1 | 진행 예정 | 불변 분류 metadata, 확장 등록, 유효성/상한 검사, 읽기 전용 pattern-catalog API | 기본/전체/미분류 catalog 및 오류·HTTP·기존 전체 회귀 통과 |
| P2 | 대기 | 분야·패턴 탐색, 프로젝트 출처 필터, 기존 URL과 새 API 없는 서버 fallback | 구형/신형 서버 브라우저 회귀, 16개 실험 접근 가능 |
| P3 | 대기 | Strategy/Case study/Scenario 관계, 프리셋·설명 등록 계약, 패턴 의미 버전/폐기 정책 | 기존 JSON/hash 불변; BEFORE/AFTER의 구체적 전략·사례 근거 표시 |
| P4 | 대기 | 공통 검증 성질·측정 범위·비교 가능 조건 모델 | 단위/환경/측정 범위 불일치 비교를 경고; 구형 증거 fallback |
| P5 | 대기 | Excel/Equity 파이프라인 공통 계약과 최소 재사용 코드 추출 | 기존 처리 결과·메모리 상한·backpressure·오류·재시도·종료 보존 |
| P6 | 대기 | 추가 사례로 패턴 재사용 검증 및 확장 가이드 갱신 | 중앙 controller/frontend 분기 없이 등록; 출처·한계 유지 |
| P7 | 대기 | 전체 인수·문서/캡처·릴리스·운영 전환 | 기본/Career 정상·실패·재시작·artifact 호환, 기존 Run 보존, 백업 검증 |

## P1 범위와 검증 단위

1. 7개 분야와 현재 16개 실험의 다중 패턴 연결을 구현한다.
2. 신뢰된 Spring contribution으로 정의를 추가하고, 현재 ExperimentCatalog와 결합한다.
3. 정의는 제공하되 활성 실험만 결과 목록에 포함한다. 미분류 확장 실험을 차단하지 않는다.
4. 기존 endpoint·descriptor·scenario·RunStore·worker·frontend를 바꾸지 않고 신규 endpoint만 제공한다.
5. 검증은 불변성/정렬/상한/중복/누락 참조, 기본 3개/전체 16개/추가 provider, HTTP 직렬화,
   기존 시나리오 실행·Run 재개방·export 회귀와 전체 `./gradlew test`로 수행한다.

P1 종료 시 화면은 기존 모듈 탐색을 유지한다. 코드 이동·범용 실행 엔진·DB migration·
운영 배포 교체는 이번 첫 단계의 완료 조건이 아니다. 후속 단계는 앞 단계의 검증 완료 후 착수한다.
