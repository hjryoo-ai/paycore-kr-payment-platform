# 11. 의존성 취약점 스캔은 푸시 게이트가 아니라 주기 실행으로 돌린다

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

**버전을 13.0.0 으로 올리고, GitHub Actions 에서는 주기 실행 + 수동 실행으로만 돌린다.**

- `secrets` job (gitleaks): 모든 push / PR 에서 돈다. 빠르고 결정적이며 외부 의존이 없다.
- `dependency-scan` job (OWASP): `schedule`(주 1회) 과 `workflow_dispatch` 에서만 돈다.
- NVD 로컬 DB 를 `actions/cache` 로 명시적으로 잡는다. `setup-gradle` 의 캐시 대상이 아니다.
  `restore` 와 `save` 를 나눠 `if: always()` 로 저장한다 — **취약점이 발견돼 job 이 실패해도
  받아둔 DB 는 남겨야 한다.** 실패 시 저장을 건너뛰면 매번 전체 재적재만 반복된다.
- `failBuildOnCVSS = 7.0` 은 그대로다. 게이트의 **강도**를 낮춘 것이 아니라 **시점**을 옮긴 것이다.

Jenkins 파이프라인(§10.1 stage 4)은 그대로 파이프라인 안에 둔다. Jenkins 에이전트는 워크스페이스가
지속되므로 NVD DB 가 빌드 간에 남고, 매 실행이 증분 갱신이다. 같은 검사라도 러너가 일회용인지
지속되는지에 따라 옳은 배치가 달라진다.

## 근거

취약점은 코드가 그대로여도 새로 발견된다. 즉 이것은 원래 **"변경 시점"이 아니라 "시간"에 걸리는
검사**다. 커밋에 묶어 두는 것이 오히려 검사의 성격과 어긋난다.

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

## 결과

- 푸시/PR CI 는 외부 API 가용성과 무관해진다.
- NVD 스캔 결과를 지금 당장 보고 싶으면 Actions 탭에서 **Run workflow** 로 돌린다.
- `NVD_API_KEY` 는 저장소 시크릿에 **반드시** 있어야 한다. 없으면 워크플로가 스캔 전에
  분명한 메시지로 먼저 죽는다 — 몇 분 태우고 `length of 0 too short` 라는 수수께끼로
  죽는 것보다 낫다.
