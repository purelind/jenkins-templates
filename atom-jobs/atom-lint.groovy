/*
* input
* @REPO(string: github repo name, Required)
* @COMMIT_ID(string: commit id request id, Required)
* @CACHE_CODE_FILESERVER_URL(string: cached code on fileserver, Required)
* @LINT_CMD(txt: cmd to run lint check, Required)
*
*
* doc
*/

properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'COMMIT_ID',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'CACHE_CODE_FILESERVER_URL',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'REPORT_DIR',
                        trim: true
                ),
                text(
                        defaultValue: 'golangci-lint run  --out-format=junit-xml  --timeout=10m  -v ./... > golangci-lint-report.xml',
                        name: 'LINT_CMD',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TRIGGER_EVENT',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'BRANCH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TASK_NAME',
                        trim: true
                ),
        ])
])


LINT_CONFIG_URL = "https://raw.githubusercontent.com/PingCAP-QE/devops-config/main/${REPO}/golangci-lint.yaml"

if (REPORT_DIR == "") {
    REPORT_DIR = "golangci-lint-report.xml"
}


def run_with_pod(Closure body) {
    def label = "lint-check-atom-job" + UUID.randomUUID().toString()
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: true,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '1000m', resourceRequestMemory: '2Gi',
                            resourceLimitCpu: '20000m', resourceLimitMemory: "20Gi",
                            command: '/bin/sh -c', args: 'cat',
                            // envVars: [containerEnvVar(key: 'GOMODCACHE', value: '/nfs/cache/mod'),
                            //           containerEnvVar(key: 'GOPATH', value: '/go')],
                            envVars:[containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                    // nfsVolume(mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                    //         serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false),
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

try {
    run_with_pod {
        container("golang") {
            try {
                def ws = pwd()

                dir("${REPO}") {
                    stage("${TASK_NAME}") {
                        println "${TASK_NAME}"
                    }
                    stage("Download code from fileserver") {
                        sh """
                        curl ${CACHE_CODE_FILESERVER_URL} | tar xz --strip-components=1
                        """
                    }

                    stage("Download lint conf") {
                        fileExist = sh(returnStatus: true, script: """
                            if curl --output /dev/null --silent --head --fail ${LINT_CONFIG_URL}; then exit 0; else exit 1; fi
                            """)
                        if (fileExist == 0) {
                            println "golangci lint config existed in devops-config"
                            sh """
                            curl -L -o .golangci.yml ${LINT_CONFIG_URL}
                            """
                        } else {
                            println "use default golangci lint config"
                        }
                    }

                    stage("Lint check") {
                        sh """
                        curl -sfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh| sh -s -- -b ${ws}/bin v1.45.2
                        """
                        sh """
                        export PATH=${ws}/bin:\$PATH
                        golangci-lint version
                        
                        ${LINT_CMD}
                        """
                    }
                }
                currentBuild.result = "SUCCESS"
                currentBuild.description = ""

            } catch (err) {
                throw err
            } finally {
                sh """
                wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-lint/agent-lint.py
                python3 agent-lint.py ${REPO}/${REPORT_DIR} || true

                wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_lint/tiinsight-agent-lint.py
                python3 tiinsight-agent-lint.py ${REPO} ${BRANCH} ${COMMIT_ID} ${TASK_NAME} ${REPO}/${REPORT_DIR} ${BUILD_URL}
                """
                def exists = fileExists "test_summary.info"
                if (exists) {
                    ENV_TEST_SUMMARY = sh(script: "cat test_summary.info", returnStdout: true).trim()
                    println ENV_TEST_SUMMARY
                    currentBuild.description = "${ENV_TEST_SUMMARY}"
                } else {
                    println "file not exist: test_summary.info"
                }
                
                junit(
                    allowEmptyResults: true,
                    testResults: "${REPO}/${REPORT_DIR}"
                )
            }
        }
    }
}

catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
        currentBuild.result = "FAILURE"
        echo "${e}"
}


