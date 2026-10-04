# Findings

[TEMPLATE.md](TEMPLATE.md)를 복사해 `<날짜>-<주제>.md`로 작성한다. 템플릿은 측정 결과가 아니며 미리 채운 성능 주장은 없다.

1. `/runs/ui`에서 저장된 Run을 열고 baseline/candidate를 선택한다.
2. 비교 화면의 조건 차이, 누락값, 상태, 단위를 확인한다.
3. 종료 Run의 NDJSON export를 보관하고 마지막 end 레코드를 확인한다. 파일 SHA-256을 계산해 Finding에 적는다.
4. 원본 artifact는 별도 보관하고 Run의 참조 metadata와 대조한다.
5. 사실·재현 조건·가설·측정 결과·해석·한계를 분리해 작성하고 코드와 함께 리뷰한다.

[API와 증거 보관 경계](../run-evidence.md)를 참고한다. UI에는 Finding 문서를 자동 생성하거나 결론을 판정하는 기능이 없다.

## 측정된 Finding

- [2026-10-04: 저장 대기 시간의 병렬화와 제한 힙 Excel ingestion](2026-10-04-pipeline-throughput-heap.md) — 16개 Run export와 체크섬, 재현 명령, 성능 우위의 적용 범위.
