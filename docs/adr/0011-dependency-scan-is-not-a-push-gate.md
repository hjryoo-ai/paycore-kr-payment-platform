# 11. 의존성 취약점 스캔을 푸시 게이트에서 뺀다

- 상태: 채택
- 날짜: 2026-08-24
- 관련: 설계문서 §10.1 (stage 4 "Dependency Scan"), §10.2 ("의존성: OWASP Dependency-Check CI 게이트")

## 맥락

GitHub Actions 의 `security` job 이 두 번 연속 실패했다. 실패 원인은 취약점 발견이 **아니었다**.

```
Failed to process CVE-2026-6785
org.owasp.dependencycheck.data.nvdcve.DatabaseException:
  Error updating 'CVE-2026-6785'; Value too long for column "URL CHARACTER VARYING(1000)":
  "'https://bugzilla.mozilla.org/buglist.cgi?bug_id=1935995%2C1999158%2C... (1585)"
> Task :dependencyCheckAggregate FAILED — Analysis failed.
```

dependency-check 10.x 의 내장 H2 스키마는 `reference.url` 을 `VARCHAR(1000)` 으로 잡는다.
NVD 에 1000자를 넘는 reference URL(Mozilla bugzilla 의 다건 조회 링크)이 들어오자 insert 가
깨졌고, 스캔 전체가 `Analysis failed` 로 끝났다. 13.0.0 에서 이 컬럼은 `VARCHAR(8000)` 이다.
(두 버전의 `data/initialize*.sql` 을 직접 열어 확인했다.)

버전을 올리면 이 실패는 사라진다. 그러나 남는 문제가 하나 더 있다.

- NVD API 는 키가 없으면 **30초당 5요청**, 있으면 50요청이다. 열 배 차이다.
- GitHub 러너는 매번 새 머신이다. 로컬 NVD DB 가 없으면 전체 적재부터 시작한다.
- 실제 소요: 최초 실행 **2시간 25분**, 캐시가 일부 남은 실행 **32분**.
- 그리고 이 시간의 결과는 우리 코드가 아니라 **외부 API 의 그날 상태**에 달려 있다.

버전을 올리고 나서 두 번째 원인이 하나 더 드러났다. **13.x 는 NVD API 키가 없으면 아예 돌지 않는다.**

```
NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key
```

추적하면 상류 버그다. `dependencycheck.properties` 에 `nvd.api.key=` 가 빈 값으로 들어 있고,
`Settings.getString` 은 그 `""` 를 그대로 돌려주는데, `NvdApiDataSource` 는

```java
final String key = settings.getString(Settings.KEYS.NVD_API_KEY);
if (key != null) { builder.withApiKey(key) ... }
```

처럼 `null` 만 보고 빈 값을 거르지 않는다. 그래서 빈 키가 NVD 로 나가고 NVD 가 `Invalid apiKey`
로 거절한다. 빌드 쪽에서 빈 값을 걸러도 소용없다 — Gradle 플러그인은 이미 `setStringIfNotEmpty`
를 쓰고 있어서, 빈 문자열의 출처가 우리 설정이 아니라 도구의 기본 properties 이기 때문이다.
즉 키는 "권장"이 아니라 **필수**다.

즉 이 검사를 푸시 게이트에 두면, 한 줄 오타를 고친 커밋이 NVD 의 가용성에 인질로 잡힌다.

## 결정

**GitHub Actions 에서는 OWASP Dependency-Check 를 걷어내고 Dependabot 으로 대체한다.
OWASP 는 Jenkins 파이프라인과 `build.gradle.kts` 에 그대로 남긴다.**

- `secrets` job (gitleaks): 모든 push / PR 에서 돈다. 빠르고 결정적이며 외부 의존이 없다.
- `dependency-graph` job: push 에서 `gradle/actions/dependency-submission` 으로 의존성
  그래프를 제출한다. GitHub 은 Gradle 멀티모듈을 저장소만 보고 정확히 읽어내지 못하므로
  빌드가 직접 해석해 올려야 Dependabot 이 실제로 동작한다.
- Dependabot 취약점 경보 + 자동 보안 수정을 저장소에서 켠다.
- `NVD_API_KEY` 시크릿은 삭제한다. GitHub Actions 에서 더 쓰지 않는다.
- Jenkins 의 `Dependency Scan` stage(§10.1 stage 4)와 `dependencyCheck { failBuildOnCVSS = 7.0 }`
  설정은 유지한다. 러너 시간이 들지 않고, "CVSS 게이트를 파이프라인에 둔다"는 것 자체가
  이 저장소가 보여주려는 것 중 하나다.

## 근거

취약점은 코드가 그대로여도 새로 발견된다. 즉 이것은 원래 **"변경 시점"이 아니라 "시간"에 걸리는
검사**다. 커밋에 묶어 두는 것이 오히려 검사의 성격과 어긋난다.

그리고 그 "시간에 걸리는 검사"를 Dependabot 이 더 잘한다. 상시 동작하고, 러너 시간을 쓰지 않고,
API 키가 필요 없고, 고칠 수 있으면 PR 까지 연다. 우리가 30분짜리 job 으로 흉내내던 것을
플랫폼이 공짜로 더 낫게 해 주는데 굳이 흉내낼 이유가 없다.

실측이 이 판단을 뒷받침한다. 하루 동안 이 job 은 **4번 실패했고 한 번도 완주하지 못했으며**
러너 시간을 3시간 넘게 썼다. 원인은 둘 다 우리 코드가 아니라 도구였다(H2 컬럼 폭, 빈 API 키).
같은 기간 Dependabot 은 스위치 하나였다.

그리고 실패를 방치하는 것이 가장 나쁘다. 매 푸시마다 빨간 X 가 뜨는 CI 는 곧 아무도 읽지 않게 되고,
그때부터는 진짜 취약점이 발견돼도 같은 빨간 X 로 묻힌다. 게이트를 유지하는 값은 그 게이트가
초록일 때만 발생한다.

## 버린 선택지

- **`failOnError = false` 로 스캔 오류를 무시한다** — 최악이다. 피드를 못 받아 CVE 를 0건 본
  실행과 정말로 0건인 실행이 똑같이 초록으로 보인다. 보안 검사가 보안 연극이 된다.
- **job 을 지운다** — 실패는 사라지지만 검사도 사라진다. 원인이 도구 버그였으므로 근거가 없다.
- **공개 NVD 미러(`nvd.datafeedUrl`)를 쓴다** — 13.0.0 에 옵션은 있으나 무료 공개 미러는 없다.
  `dependencycheck.properties` 의 기본값도 `#nvd.api.datafeed.url=https://example.com/nvd-cache/`
  로 주석 처리된 예시일 뿐이고, `open-vulnerability-cli` 는 **직접 호스팅**하라는 도구다.
  미러를 운영할 생각이면 그때 다시 본다.

## 주기 실행을 뺀 것에 대하여 (2026-08-24 수정)

처음 이 ADR 은 주 1회 `schedule` 을 두었다. 저장소 소유자의 결정으로 그 트리거를 제거했고,
그 결과 GH Actions 의 OWASP job 은 "아무도 누르지 않으면 영원히 돌지 않는" 것이 되었다.
그 상태의 job 은 커버리지가 아니라 장식이므로 아예 걷어내고 Dependabot 으로 갈음했다.

즉 잃었던 시간축은 스케줄이 아니라 Dependabot 으로 복구된다.

## 결과

- 푸시/PR CI 는 외부 API 가용성과 무관해진다. NVD 를 더 이상 호출하지 않는다.
- 의존성 취약점은 Dependabot 이 상시로 본다 — 코드를 건드리지 않아도 새 CVE 가 뜨면 알린다.
- OWASP 를 손으로 돌려보고 싶으면 `NVD_API_KEY` 를 환경변수로 두고
  `./gradlew dependencyCheckAggregate` 를 로컬에서 돌린다. 키 없이는 동작하지 않는다.
- 남은 위험: 제출된 의존성 그래프가 곧 Dependabot 의 시야다. `dependency-graph` job 이
  조용히 깨지면 경보도 조용히 멈춘다. 이 job 은 초록이어야 의미가 있다.
- `NVD_API_KEY` 는 저장소 시크릿에 **반드시** 있어야 한다. 없으면 워크플로가 스캔 전에
  분명한 메시지로 먼저 죽는다 — 몇 분 태우고 `length of 0 too short` 라는 수수께끼로
  죽는 것보다 낫다.
