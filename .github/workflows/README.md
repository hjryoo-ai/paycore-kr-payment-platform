# CI 워크플로

`ci.yml` 은 `Jenkinsfile` 과 **같은 단계**를 돈다. 두 벌을 두는 이유는 설계 §10.1 그대로다 —
Jenkins 는 사내 파이프라인 시연용, GitHub Actions 는 공개 저장소 뱃지용이다.

| 단계 | Jenkins | Actions |
|---|---|---|
| 빌드 | `Build` | `verify` |
| 포맷 | `Static Analysis` | `verify` |
| 단위 테스트 | `Unit Test` | `verify` |
| 통합 테스트 | `Integration Test` | `integration` |
| 의존성 스캔 | `Dependency Scan` | `security` |
| 시크릿 스캔 | (pre-commit) | `security` (gitleaks) |
| 패키징 | `Package` | `smoke` |
| 배포 + 스모크 | `Deploy (local) + Smoke` | `smoke` |

## 필요한 시크릿

| 이름 | 용도 | 없으면 |
|---|---|---|
| `NVD_API_KEY` | OWASP Dependency-Check 의 NVD 조회 속도 | 스캔이 매우 느려진다 (실패하지는 않는다) |

Jenkins 쪽은 같은 값을 `paycore-nvd-api-key` 자격증명으로 등록한다.
