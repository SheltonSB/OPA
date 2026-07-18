pipeline {
  agent { docker { image 'maven:3.9.11-eclipse-temurin-21' } }
  options { timeout(time: 30, unit: 'MINUTES'); disableConcurrentBuilds(abortPrevious: true) }
  stages {
    stage('Verify') { steps { sh 'mvn --batch-mode --no-transfer-progress verify' } }
    stage('OPA Guard') {
      when { changeRequest() }
      steps {
        sh 'mkdir -p .tools && curl -fsSLo .tools/opa https://openpolicyagent.org/downloads/v1.18.2/opa_linux_amd64_static'
        sh "echo '9903e5125ac281104f2c4b7371d10cc3b74a98933743fcbfc174f9bf0ab20de8  .tools/opa' | sha256sum -c - && chmod 0755 .tools/opa"
        sh 'git fetch origin ${CHANGE_TARGET}'
        sh 'git worktree add --detach .opa-guard-baseline origin/${CHANGE_TARGET}'
        sh '''java -jar target/opa-policy-performance-guard-*.jar \
          --spring.config.additional-location=file:opa-guard.yml \
          --opa-guard.opa-executable=.tools/opa \
          --opa-guard.baseline-policy=.opa-guard-baseline/policy \
          --opa-guard.candidate-policy=policy \
          --opa-guard.benchmark-dataset=benchmark/dataset.json'''
      }
      post { always { archiveArtifacts artifacts: 'build/reports/opa-guard.*', allowEmptyArchive: true } }
    }
  }
}
