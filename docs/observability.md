# 관측 schema와 JVM 스냅샷

5단계 첫 작업은 공통 단위 catalog와 선택적 JVM 시작/종료 관측이다. 주기적 JVM sampling, 명시적 trace, JFR/log 수집, 외부 인프라는 후속 작업이다.

## Metric catalog v1

`GET /metrics/schema`는 이름·단위·범위(run/process)를 제공한다. 비교 API도 같은 catalog를 쓴다. ms/ns, bytes/MiB, percent, rate를 구분하며 알 수 없는 이름은 unknown이다. 기존 MetricPoint와 DB schema는 변경하지 않는다. legacy counters/gauges는 저장 컨테이너일 뿐 의미론적 counter/gauge 보장은 아니다(예: throughput rate가 counters에 존재).

기존 heap `Mb` 필드는 실제 1024² byte 기준이므로 MiB로 설명한다. 기존 metric의 placeholder/구현 누락을 catalog가 실제 계측으로 바꾸지는 않는다. 특히 pipeline connection/lock/retry 지표는 구현 상태를 함께 확인한다. schema version은 새로운 Run의 parameters에 남기며 기존 실행에는 소급 기록하지 않는다.

## JVM 경계 관측 설계

- 기본값 `lab.observation.jvm-enabled=false`. true면 실행 직전/직후 각 한 번, 최대 두 관측을 기존 bounded metric writer에 보낸다.
- MXBean의 heap used/committed/max bytes, live platform thread 수, 누적 GC collection count/time을 수집한다. 지원하지 않는 음수 값은 0으로 바꾸지 않고 생략한다.
- 프로세스 전체 값이다. 동시 실험·서버·writer 작업이 섞이며 Run별 할당량/CPU/virtual thread 수/GC pause 분포를 의미하지 않는다. GC time은 JVM 시작 후 누적 collection time이며 wall-clock pause latency와 다르다.
- 추가 scheduler, 작업 queue, 주기 thread를 만들지 않는다. 시작/종료 관측 사이의 peak는 관측하지 못한다.
- 수집 시간(ns)과 sample 수를 summary에 기록한다. 수집/저장 실패는 성공으로 숨기지 않는다. 종료 관측 후 writer를 drain하므로 완료 이후 관측을 게시하지 않는다.
- labels는 source/scope/phase/schemaVersion만 사용하고 스택·thread 이름·환경변수·JVM 인자·파일을 수집하지 않는다.
- 켜짐/꺼짐과 schema version을 Run parameters에 기록해 비교 화면에서 조건 차이를 볼 수 있게 한다.
