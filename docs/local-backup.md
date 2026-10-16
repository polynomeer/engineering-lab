# 로컬 배포 백업·복원 자동화

`backup-local-deployment.py`는 [LaunchAgent로 관리하는 배포](local-service.md)를 잠시
정지해 일관된 파일 백업을 만들고 다시 시작한다. 기본은 매일 03:00, 최근 성공 백업
7개 보존이다. 예약 시각은 macOS의 로컬 시간이며 현재 호스트는 Asia/Seoul(KST)이다.
로그인 세션이 필요하다. 로그아웃 중 예약 실행이나 호스트 전원 꺼짐 동안의 실행을
보장하지 않는다. 잠자기에서 깨어날 때 launchd가 놓친 실행을 합칠 수 있다.

## 백업 범위와 한계

포함: `store/`의 H2 DB·CHECK·관리 REPORT/JFR/log·worker workspace,
`controller.jar`, `frontend/`, `application.properties`, `deployment.json`,
존재하는 경우 `acceptance/` 검증 기록. 소유권 잠금 `store/owner.lock`은 복사하지 않는다.

제외: Docker 이미지/볼륨, 원본 입력 XLSX, 메모리 ingestion DB, 브라우저 localStorage,
LaunchAgent plist·운영 로그와 런타임(Java/Node/Python) 자체. 복원 시 같은 Docker image ID의
로컬 이미지와 런타임이 필요하다. 실행 증거와 동결 배포물의 **동일 호스트 복원**을 검증한
범위다. `.lab/backups/`는 원본과 같은 디스크이므로 디스크 고장에 대비하려면 별도 볼륨이나
외부 저장소에 완성된 백업 디렉터리를 추가 보관한다. 자동 외부 전송·암호화는 포함하지 않는다.

## 수동 백업과 정기 실행

저장소 루트에서 실행한다. Python 3.11 이상, 기존 LaunchAgent 설치가 필요하다.

```bash
python3 scripts/backup-local-deployment.py backup \
  .lab/deployments/local-20261016 .lab/backups/local-20261016 --keep 7
python3 scripts/backup-local-deployment.py schedule \
  .lab/deployments/local-20261016 .lab/backups/local-20261016 --keep 7 --hour 3
```

백업 폴더는 배포 디렉터리 외부여야 한다. 복사와 검증은 최대 10 GiB/10만 entry,
디렉터리 깊이 64, 파일별 1 MiB 버퍼로 제한한다. metadata는 최대 32 MiB다. 파일 수와
예상 크기, 남은 디스크 공간도 확인한다. 링크·특수 파일은 거절한다.

1. 배포별 잠금으로 중복 실행을 거절한다. 양쪽 관리 서비스가 등록돼 있어야 한다.
2. Run 목록을 페이지 순회하며 실행 중 실험이 있으면 해당 회차를 `SKIPPED`로 끝낸다.
   조회 실패도 서비스를 정지시키지 않는다. 확인 직후 새 접수가 들어오면 기존 graceful
   shutdown 정책으로 처리되므로 백업 시간에는 새 실험을 접수하지 않는다.
3. 재개 의도를 `backup-resume.json`에 기록하고 frontend/API를 정상 정지한다.
4. Java와 호환되는 POSIX `owner.lock`을 획득한다. 다른 앱이 저장소를 쓰면 복사하지 않는다.
5. `.partial` 폴더에 복사하고 manifest의 파일별 byte/SHA-256과 controller hash를
   검증한 뒤 완성 이름으로 rename한다. 불완전한 복사본은 성공 백업으로 취급하지 않는다.
6. 성공·실패와 관계없이 기존 서비스를 다시 시작하고 API 응답을 확인한다.
7. 새 백업 성공 후 동일 배포의 오래된 정상 백업만 최근 `--keep`개를 남기고 제거한다.
   손상된 백업·`.partial`·다른 배포 백업은 임의 삭제하지 않는다.

예약 job은 `com.pnomeer.engineering-lab.backup`이며 서비스 도구와 백업 도구의 사본을
배포의 `backup-tools-*/`에 고정한다. 소스 변경은 설치된 job에 자동 반영되지 않는다.
현재 작업이 끝난 뒤 `unschedule`→`schedule`로 갱신한다. 기존 plist는 덮어쓰지 않는다.

```bash
launchctl print gui/$(id -u)/com.pnomeer.engineering-lab.backup
# 예약과 동일한 경로로 즉시 1회 실행; 실행 중에는 다시 요청하지 않는다.
launchctl kickstart gui/$(id -u)/com.pnomeer.engineering-lab.backup
python3 scripts/backup-local-deployment.py unschedule .lab/deployments/local-20261016
```

예약 제거는 백업 실행 중에는 거절하며 기존 백업과 도구 사본은 보존한다. 서비스 자체를
제거하거나 배포를 바꾸기 전 먼저 예약을 제거한다. API/frontend의 `stop`은 백업 예약을
삭제하지 않지만, 다음 백업은 중지된 서비스를 임의로 활성화하지 않고 실패한다.

최신 성공: `backup-last-success.json`. stdout/stderr:
`services/backup.stdout.log`, `services/backup.stderr.log`. 실패는 0이 아닌 exit code로
남는다. 자동 알림 전송과 운영 로그 회전은 구현하지 않았다. SKIPPED는 성공 백업 생성이
아니므로 최신 성공 시각과 로그를 함께 확인한다.

## 실패 후 서비스 재개

일반 오류와 SIGTERM에서는 finally로 서비스를 재개한다. SIGKILL·전원 장애로 중간에
종료되면 재개 journal이 남을 수 있다. 다음 backup은 먼저 재개를 시도하며, 즉시 복구는:

```bash
python3 scripts/backup-local-deployment.py resume .lab/deployments/local-20261016
python3 scripts/manage-local-service.py status .lab/deployments/local-20261016
```

재개 실패 시 journal을 남기고 오류를 반환한다. 의미를 확인하지 않고 journal을 지우지
않는다. `.partial` 폴더는 원인을 확인한 뒤 수동 정리한다. 예약 job이 현재 실행 중인지
확인하고 같은 배포를 여러 운영 명령으로 동시에 변경하지 않는다.

## 검증과 비파괴 복원

완성된 `backup-<UTC시각>-<UUID>` 경로를 지정한다. 기존 디렉터리 위에 복원하지 않는다.

```bash
python3 scripts/backup-local-deployment.py verify /absolute/path/to/backup-directory
python3 scripts/backup-local-deployment.py restore /absolute/path/to/backup-directory \
  .lab/restores/recovered-lab --port 18089
python3 scripts/run-local-deployment.py .lab/restores/recovered-lab api
# 다른 터미널에서 실행; 원본 frontend와 다른 포트
python3 scripts/run-local-deployment.py .lab/restores/recovered-lab frontend --frontend-port 4189
```

복원 전과 복사 중 hash/크기를 대조한다. 성공한 복원본만 공개하고 실패한 `.partial-*`는
보존한다. storage root와 API 포트를 새 경로로 변경하며 원본 DB·서비스·이미지는 건드리지
않는다. 복사된 자원 소유권 journal로 원본 컨테이너를 정리하지 않도록 worker의
`recover-on-startup`은 모두 false로 바꾼다. 미완료 자원 journal이 있는 백업은 조사 후
해당 배포의 복구 정책을 적용해야 한다. 이 설정을 무조건 true로 변경하지 않는다.

기존 전체 모듈 인수 snapshot이 있으면 복원본에서 동일 증거를 검증한다.

```bash
python3 scripts/accept-local-deployment.py --api http://127.0.0.1:18089 \
  --output .lab/restores/recovered-lab/acceptance --verify-reopen
```

운영 전환은 복원 검증 후 원본 예약을 제거하고 원본 서비스를 정상 정지/제거한 뒤,
복원본을 `manage-local-service.py install`로 등록한다. 원본 배포는 되돌리기 위해 보존한다.
이 도구는 복원만으로 운영 포트를 탈취하거나 서비스를 자동 교체하지 않는다.

## 실제 검증 — 2026-10-16

- 백업 도구 실패 경계 11건 통과: 손상·링크·특수 파일·경로 탈출·복사 중 변조·기존 복원 경로
  거절, 복사 상한, 최근 백업 보존, 동시 실행 거절, active Run 건너뛰기,
  복사 실패 시 서비스 재개와 재개 실패 journal 보존.
- 실행 중 Java의 `owner.lock`을 Python POSIX lock으로 획득할 수 없음을 실제 확인했다.
- 수동 실행과 LaunchAgent 예약 실행 경로로 각각 백업을 만들었다. 예약 job의 exit code는 0.
  최신 백업은 387개 파일/67,680,338 bytes이며 파일별 hash 검증을 통과했다.
- `.lab/restores/local-20261016-check`에 새로 복원하고 API 18089/frontend 4189에서
  실제 기동했다. 기존 17개 Run의 JSON·REPORT·canonical export hash가 모두 같았고
  8개 모듈 READY, HTML/API proxy 응답을 확인했다. 원본 18088/4188도 정상 재개됐다.
- 예약 제거 후 백업 2개 보존과 재등록을 확인했다. 최종 설정은 매일 03:00 KST,
  최근 7개 보존이다. 미래 예약 시각의 실행 자체는 아직 관측하지 않았으며 동일 job을
  `launchctl kickstart`로 실행해 검증했다.
- 복원 검증용 프로세스는 정상 종료했고 복원 데이터는 보존했다. 원본 관리 서비스는
  계속 실행 중이다. 로컬 요약은 `backup-acceptance.json`, 복원 증거는 복원본의
  `acceptance/reopen.json`에서 확인한다.
- `./gradlew test` 성공(루트 385개 기존 성공 결과 UP-TO-DATE). 기존 배포 도구 3건과
  서비스 도구 6건도 회귀 검증을 통과했다. Career 모듈과 빌드는 변경하지 않았다.
