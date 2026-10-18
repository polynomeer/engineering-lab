# 0003. 캡처 정리는 미리보기 토큰과 opt-in으로만 삭제한다

상태: 채택 · 2026-10-18

## 맥락

JFR과 Run 로그는 자동 보관 기간이 없어 `.lab/`이 계속 커진다. 반면 이 저장소의 목적은 증거 보존이고, 지운 artifact는 되살릴 수 없다. Finding 문서가 어떤 Run을 인용하는지는 서버가 알 수 없다.

## 결정

- 대상은 종료 후 N일이 지난 Run의 `jfr_*`, `log_*` artifact뿐이다. Worker REPORT, metric, dead-letter, Run 행은 지우지 않는다. 기준선 묶음의 Run은 제외한다.
- 미리보기는 항상 가능하고 후보 목록의 SHA-256 토큰을 돌려준다. 삭제는 `lab.retention.apply-enabled=true`이고 토큰이 현재 후보와 같을 때만 수행한다.
- artifact 참조를 먼저 지우고 파일을 지운다. 삭제마다 `retention-log.ndjson`에 기록한다.

## 결과

- 실수로 인한 대량 삭제와 미리보기 이후 바뀐 목록의 삭제를 막는다.
- 자동 주기 실행은 없다. 운영자가 미리보기를 보고 실행한다.
- Finding이 인용한 Run은 기준선으로 지정하거나 export·artifact를 별도 보관해야 한다.
