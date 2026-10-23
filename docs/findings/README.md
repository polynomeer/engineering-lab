# Findings

[TEMPLATE.md](TEMPLATE.md)를 복사해 `<날짜>-<주제>.md`로 작성한다. 템플릿은 측정 결과가 아니며 미리 채운 성능 주장은 없다.

1. `/runs/ui`에서 저장된 Run을 열고 baseline/candidate를 선택한다.
2. 비교 화면의 조건 차이, 누락값, 상태, 단위를 확인한다.
3. 종료 Run의 NDJSON export를 보관하고 마지막 end 레코드를 확인한다. 파일 SHA-256을 계산해 Finding에 적는다.
4. 원본 artifact는 별도 보관하고 Run의 참조 metadata와 대조한다.
5. 사실·재현 조건·가설·측정 결과·해석·한계를 분리해 작성하고 코드와 함께 리뷰한다.

[API와 증거 보관 경계](../run-evidence.md)를 참고한다.

대시보드의 Run 비교와 반복 측정 비교 화면은 `Finding 초안 (.md)`을 내려받을 수 있다. 초안은 기록된 사실만 채운다: Run·묶음 ID, Git commit/dirty, JVM/OS, 정의·입력 SHA-256, 다른 조건, 측정값과 반복 횟수·변동, 범위 겹침 판정. 문제·가설·해석·결정은 `<작성 필요>`로 남기며 결론을 자동으로 판정하지 않는다. export 보관 위치와 SHA-256은 직접 채운다. `증거 묶음 (.zip)`은 같은 초안과 모든 Run export, 검증된 캡처, 통계, `SHA256SUMS`를 zip 하나로 내려받는다. 묶음 파일을 보관하고 그 SHA-256을 Finding에 적으면 된다.

서버는 이 디렉터리의 문서가 적은 Run ID를 색인해 Run 상세에 "이 Run을 인용한 Finding"으로 보여 주고, 그 Run의 JFR·로그 캡처를 보관 정리에서 제외한다. Run ID는 전체 UUID로 적어야 색인된다.

## 측정된 Finding

- [2026-10-04: 저장 대기 시간의 병렬화와 제한 힙 Excel ingestion](2026-10-04-pipeline-throughput-heap.md) — 16개 Run export와 체크섬, 재현 명령, 성능 우위의 적용 범위.
