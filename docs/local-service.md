# macOS 상시 실행

[로컬 배포](local-deployment.md)의 고정 산출물을 현재 사용자의 LaunchAgent 두 개로
실행한다. macOS 로그인 시 자동 기동하고 API/프론트엔드 프로세스 종료 후 재기동한다.
LaunchAgent이므로 로그인 전·로그아웃 중 실행과 잠자기 중 서비스 제공은 보장하지 않는다.
재부팅 후에는 사용자가 로그인해야 한다. 관리자 권한이나 시스템 LaunchDaemon은 사용하지 않는다.

## 설치

먼저 배포 준비를 완료하고 기존 수동 API/프론트엔드를 정상 종료한다. 작업 중인 실험이
없을 때 전환한다. 설치 도구는 점유된 포트를 거절하며 기존 프로세스를 임의로 종료하지 않는다.

```bash
python3 scripts/manage-local-service.py install .lab/deployments/local-20261016 \
  --node /absolute/path/to/node
python3 scripts/manage-local-service.py status .lab/deployments/local-20261016
```

`--node`를 생략하면 현재 PATH의 Node 24를 사용한다. Python 3.11 이상과 Java 25,
Docker CLI가 필요하다. Java는 `LAB_JAVA25_HOME` 또는 macOS Java 25 탐색 결과를 쓴다.
GUI 로그인 세션에서 현재 사용자로 실행하며 sudo로 실행하지 않는다.

- `~/Library/LaunchAgents/com.pnomeer.engineering-lab.api.plist`
- `~/Library/LaunchAgents/com.pnomeer.engineering-lab.frontend.plist`

한 사용자에게 하나의 배포만 등록한다. 기존 plist나 같은 label의 서비스가 있으면
설치를 거절한다. 런타임 절대 경로·Docker PATH와 배포 경로를 명시해 셸 설정에
의존하지 않는다. 배포의 `services/runner.py`는 실행 도구의 사본이며 매 API 시작 시
controller jar hash를 검사한다. 원본 저장소의 스크립트 변경은 설치된 사본에 자동 반영되지 않는다.
런타임을 삭제/업그레이드해 경로가 바뀌면 서비스를 제거하고 다시 설치한다.

Docker Desktop 자체의 자동 시작 설정은 변경하지 않는다. Career 실행에는 Docker가
별도로 준비돼 있어야 한다. 서비스가 실행 중이어도 Docker나 이미지가 준비되지 않았으면
Career readiness가 실패할 수 있다. API/프론트엔드 자동 재기동은 Docker 장애를 복구하거나
멈춘 HTTP 요청을 감지하는 health watchdog이 아니다.

## 운영 명령

```bash
python3 scripts/manage-local-service.py status .lab/deployments/local-20261016
python3 scripts/manage-local-service.py stop .lab/deployments/local-20261016
python3 scripts/manage-local-service.py start .lab/deployments/local-20261016
python3 scripts/manage-local-service.py uninstall .lab/deployments/local-20261016
```

- `status`: 등록 상태·프로세스 상태·PID·직전 종료 코드를 출력한다. HTTP readiness 판정은 별도다.
- `stop`: frontend→API 순서로 disable/bootout한다. 다음 로그인에도 정지 상태를 유지한다.
- `start`: enable 후 미등록 구성요소만 bootstrap한다. 이미 실행 중이면 중복 실행하지 않는다.
- `uninstall`: 소유권을 확인한 두 서비스와 plist만 제거한다. 배포 이미지·store·로그는 보존한다.
- 설정 파일 hash나 loaded 서비스의 plist 경로가 다르면 중지/제거도 거절한다. 먼저 변경 원인을 조사한다.

KeepAlive/RunAtLoad로 종료 후 자동 재기동하며, 반복 실패 시 launchd의 30초 throttle을
적용한다. 명시적 종료는 PID kill 대신 `stop`을 사용한다. bootout 시 SIGTERM 후 최대
60초의 종료 유예를 준다. API는 기존 graceful shutdown 및 저장소 복구 정책을 유지한다.
frontend와 API는 독립 서비스이므로 시작·재기동 도중 frontend에서 잠시 502가 날 수 있다.

로그는 배포 폴더의 `services/api.stdout.log`, `api.stderr.log`,
`frontend.stdout.log`, `frontend.stderr.log`에 저장된다. `owner.json`에는 등록 소유권과
plist/runner hash를 보관한다. 로그 자동 회전·보존 삭제는 아직 없으며 파일 크기를
모니터링한다. 백업/보존 자동화는 별도 운영 작업이다.

설치 도중 실패하면 기록을 보존한다. `status`로 확인한 뒤 `uninstall`로 소유 자원만
제거한다. 제거된 `services/`에는 `uninstalled-owner.json`과 로그가 남는다. 재설치할
때는 해당 폴더를 별도 이름으로 옮겨 보존한 후 `install`을 실행한다. 활성 서비스의
폴더나 store를 옮기지 않는다.

```bash
# uninstall 성공 후에만 실행; 기존 archive 이름을 덮어쓰지 않는다.
service_archive=".lab/deployments/local-20261016/services-archive-$(date +%Y%m%d-%H%M%S)"
test ! -e "$service_archive" && \
  mv .lab/deployments/local-20261016/services "$service_archive"
```

준비 완료 확인: [대시보드](http://127.0.0.1:4188/app/experiments),
[모듈 readiness](http://127.0.0.1:18088/dashboard/v1/modules).

## 실제 검증 — 2026-10-16

`local-20261016`의 수동 프로세스를 정상 종료하고 LaunchAgent로 전환했다.

| 검증 | 결과 |
| --- | --- |
| 서비스 도구 테스트 | 경로/재기동 정책, 사용 중 포트 거절, plist 변조·loaded identity 불일치 시 변경 차단, 중지 순서, 비동기 bootout 대기 6건 통과 |
| plist / 기동 | `plutil -lint` 두 파일 통과; API·frontend 모두 running |
| 자동 재기동 | 실행 중인 실험이 없는 상태에서 두 프로세스 SIGKILL; 새 PID로 자동 재기동, 8개 모듈 READY 복원 |
| 명시적 중지 | 두 서비스 미등록 및 launchd disabled 확인; start로 다시 등록 |
| 제거 / 재설치 | 두 plist 제거, controller/store/log 보존 확인 후 로그 archive·재설치 성공 |
| 증거 보존 | 기존 17개 Run JSON·REPORT·canonical export hash 재대조 통과 |
| HTTP | frontend HTML·빌드 JS asset·API proxy 및 8개 모듈 READY 확인 |
| 루트 회귀 | `./gradlew test` 성공, 385개 기존 성공 결과 UP-TO-DATE |

종료 직후 TCP 잔여 상태를 설치 충돌로 오인한 검사와 bootout의 비동기 완료 처리를
검증 중 보완했다. 소유권 불일치 시 변경 거절 정책은 유지했다.

로컬 검증 기록은 배포의 `service-restart-verification.json`,
`service-lifecycle-verification.json`, `acceptance/reopen.json`에 보존한다.
이전 설치 로그는 `services-archive-20261016/`, 최종 서비스 로그는 `services/`에 있다.
로그인 자동 실행은 plist의 RunAtLoad/KeepAlive와 실제 bootstrap으로 확인했다.
사용자 세션을 종료하거나 호스트를 재부팅하는 시험은 수행하지 않았다.

정기 백업과 서비스 정지/재개의 연계는 [백업·복원 자동화](local-backup.md)를 따른다.
서비스 제거·배포 교체 전에 백업 예약도 제거한다.
