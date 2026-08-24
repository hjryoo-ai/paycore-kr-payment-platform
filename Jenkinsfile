// PayCore-KR 파이프라인 (docs/payment-platform-design.md §10.1)
//
// 단계 순서에 이유가 있다: 싼 검증을 먼저 돌린다. 포맷 위반 하나 때문에 Oracle 컨테이너를
// 띄우는 통합 테스트를 20분 돌리고 나서 실패하는 것은 낭비다.
pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        // 통합 테스트가 Testcontainers 로 Oracle/Kafka/Artemis 를 띄운다. 넉넉히 잡되 무한은 아니다.
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    environment {
        GRADLE_OPTS = '-Dorg.gradle.daemon=false'
        TZ = 'Asia/Seoul'
        IMAGE_TAG = "${env.GIT_COMMIT ? env.GIT_COMMIT.take(12) : 'local'}"
        // Dependency-Check 는 NVD API 키가 없으면 극도로 느려진다. Jenkins 자격증명에서 주입한다.
        NVD_API_KEY = credentials('paycore-nvd-api-key')
    }

    stages {
        stage('Build') {
            steps {
                sh './gradlew clean build -x test --no-daemon'
            }
        }

        stage('Static Analysis') {
            steps {
                // 포맷은 협상 대상이 아니다. 실패하면 spotlessApply 후 다시 올린다.
                sh './gradlew spotlessCheck --no-daemon'
            }
        }

        stage('Unit Test') {
            steps {
                sh './gradlew :common:test :payment-core:test --no-daemon'
            }
            post {
                always {
                    junit testResults: '**/build/test-results/test/TEST-*.xml', allowEmptyResults: false
                }
            }
        }

        stage('Integration Test') {
            steps {
                // Testcontainers 가 도커 소켓을 쓴다. 에이전트에 docker 가 있어야 한다.
                sh './gradlew :payment-api:test :clearing-gateway:test :clearing-simulator:test ' +
                   ':ledger-service:test :recon-batch:test --no-daemon'
            }
            post {
                always {
                    junit testResults: '**/build/test-results/test/TEST-*.xml', allowEmptyResults: false
                    archiveArtifacts artifacts: '**/build/reports/tests/test/**', allowEmptyArchive: true
                }
            }
        }

        stage('Dashboard') {
            steps {
                // tsc --noEmit 이 build 스크립트에 포함되어 있다 — 타입 오류는 빌드 실패다.
                dir('ops-dashboard') {
                    sh 'npm ci'
                    sh 'npm test'
                    sh 'npm run build'
                }
            }
        }

        stage('Dependency Scan') {
            steps {
                // CVSS 7 이상이면 빌드를 깬다 (build.gradle.kts 의 failBuildOnCVSS).
                // 여기서는 파이프라인 안에 둘 수 있다 — Jenkins 에이전트는 워크스페이스가 지속돼
                // NVD 로컬 DB 가 빌드 간에 남고 매 실행이 증분 갱신이다. 러너가 일회용인
                // GitHub Actions 에서는 같은 검사를 주기 실행으로 뺐다 (ADR-0011).
                sh './gradlew dependencyCheckAggregate --no-daemon'
            }
            post {
                always {
                    archiveArtifacts artifacts: 'build/reports/dependency-check-report.*', allowEmptyArchive: true
                }
            }
        }

        stage('Package') {
            steps {
                sh './gradlew bootJar -x test --no-daemon'
                sh 'docker compose build'
                script {
                    ['payment-api', 'clearing-gateway', 'clearing-simulator', 'ledger-service', 'recon-batch',
                     'ops-dashboard']
                        .each { svc ->
                            sh "docker tag paycore/${svc}:local paycore/${svc}:${env.IMAGE_TAG}"
                        }
                }
            }
        }

        stage('Deploy (local) + Smoke') {
            steps {
                sh 'docker compose down -v || true'
                sh 'docker compose up -d'
                sh 'scripts/wait-for-healthy.sh 600'
                // 스모크는 "떴는가"가 아니라 "돈이 끝까지 흘렀는가"를 본다.
                sh 'scripts/smoke-test.sh'
            }
            post {
                always {
                    sh 'docker compose logs --no-color --tail 500 > compose-logs.txt || true'
                    archiveArtifacts artifacts: 'compose-logs.txt', allowEmptyArchive: true
                    sh 'docker compose down -v || true'
                }
            }
        }
    }

    post {
        failure {
            echo '파이프라인 실패. 통합 테스트 리포트와 compose-logs.txt 를 먼저 본다.'
        }
    }
}
