# Dashboard read API fixtures

`fixtures/evidence.json`은 실제 서버 DTO의 고정된 합성 응답이다. Run 상세·목록 cursor·metric cursor·artifact 참조 형태를 Java `DashboardContractTest`에서 HTTP 응답과 대조한다. export의 Content-Type/Content-Disposition/end 레코드, 비교 조건·단위와 400/404도 검증한다.

프론트엔드 단위 테스트도 같은 fixture를 사용한다. 런타임의 실제 측정 결과나 성능 근거가 아니며, fixture 변경은 API 호환성을 검토한 뒤 Java/TypeScript 양쪽에서 검증한다. 운영 데이터나 사용자 경로·토큰을 넣지 않는다.
