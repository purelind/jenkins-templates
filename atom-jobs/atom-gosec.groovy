/*
* input
* @REPO(string: github repo name, Required)
* @COMMIT_ID(string: commit id request id, Required)
* @CACHE_CODE_FILESERVER_URL(string: cached code on fileserver, Required)
* @CMD(txt: cmd to run gosec check, Required)
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
                        defaultValue: 'gosec -fmt=junit-xml -out=results.xml -stdout -verbose=text -exclude=G103,G104,G204,G307,G401,G404,G501,G505,G601 ./...  || exit 0',
                        name: 'CMD'
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
    def label = "gosec-check-atom-job" + UUID.randomUUID().toString()
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
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                    nfsVolume(mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false),
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

                    stage("Gosec check") {
                        sh """
                        curl -sfL https://raw.githubusercontent.com/securego/gosec/master/install.sh | sh -s -- -b ${ws}/bin v2.11.0
                        """
                        sh """
                        export PATH=${ws}/bin:\$PATH
                        gosec -version
                        
                        ${CMD}
                        """
                    }

                }
                currentBuild.result = "SUCCESS"

            } catch (err) {
                throw err
            } finally {
                sh """
                    wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-gosec/agent-gosec.py
                    python3 agent-gosec.py ${REPO}/results.xml
                """
                ENV_GOSEC_SUMMARY = sh(script: "cat test_summary.info", returnStdout: true).trim()
                println ENV_GOSEC_SUMMARY
                currentBuild.description = "${ENV_GOSEC_SUMMARY}"

                sh """
                    wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_gosec/tiinsight-agent-gosec.py
                    python3 tiinsight-agent-gosec.py ${REPO} ${BRANCH} ${COMMIT_ID} ${REPO}/results.xml ${BUILD_URL}
                """

                junit(
                        allowEmptyResults: true,
                        testResults: "${REPO}/results.xml"
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
