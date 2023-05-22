/*
* input
* @REPO(string: github repo name, Required)
* @COMMIT_ID(string: commit id request id, Required)
* @CACHE_CODE_FILESERVER_URL(string: cached code on fileserver, Required)
* @CYCLO_CMD(txt: cmd to run Circle Complexity check, Required)
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
                text(
                        defaultValue: 'gocyclo -over 10 -avg ./ || true',
                        name: 'CYCLO_CMD'
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


def run_with_pod(Closure body) {
    def label = "circle-complexity-atom-job" + UUID.randomUUID().toString()
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
                            resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                            resourceLimitCpu: '4000m', resourceLimitMemory: "8Gi",
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOMODCACHE', value: '/nfs/cache/mod'),
                                      containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/git', readOnly: false),
                    nfsVolume(mountPath: '/nfs/cache', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs', readOnly: false),
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

                    stage("Download gocyclo") {
                        sh """
                        go install github.com/fzipp/gocyclo/cmd/gocyclo@latest
                        """
                    }

                    stage("Cyclo check") {
                        sh """
                        export PATH=${ws}/go/bin:\$PATH
                        ${CYCLO_CMD}
                        """
                    }
                    
                }
                currentBuild.result = "SUCCESS"
            } catch (e) {
                println "error: ${e}"
                throw e
            } finally {
                println "finally"
                sh """
                wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_cyclo/tiinsight-agent-cyclo.py
                python3 tiinsight-agent-cyclo.py ${REPO} ${BRANCH} ${COMMIT_ID} ${REPO}/repo_cyclo.log ${BUILD_URL}
                """
                sh """
                wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-cyclo/agent-cyclo.py
                python3 agent-cyclo.py ${REPO}/repo_cyclo.log
                """
                ENV_CYCLO_SUMMARY = sh(script: "cat cyclo_summary.info", returnStdout: true).trim()
                println ENV_CYCLO_SUMMARY
                currentBuild.description = "${ENV_CYCLO_SUMMARY}"
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
