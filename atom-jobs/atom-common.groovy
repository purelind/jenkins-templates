/*
* input
* @REPO(string: github repo name, Required)
* @COMMIT_ID(string: commit id request id, Required)
* @CACHE_CODE_FILESERVER_URL(string: cached code on fileserver, Required)
* @COMMON_CMD(txt: common cmd to run, Required)
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
                        name: 'IMAGE',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TARGET_BRANCH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'SECRET_VARS',
                        trim: true
                ),
                text(
                        defaultValue: 'echo success',
                        name: 'COMMON_CMD'
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
    def label = "common-atom-job" + UUID.randomUUID().toString()
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
    if (IMAGE.length() > 2) {
        pod_go_docker_image = IMAGE
    }
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            resourceLimitCpu: '2000m', resourceLimitMemory: "4Gi",
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/git', readOnly: false),
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
                credentialList =[]
                varStrings = SECRET_VARS.split(",")
                for (varString in varStrings) {
                    keyAndValue = varString.split(":")
                    if (keyAndValue.length == 2) {
                        credentialList.push(string(credentialsId: keyAndValue[0], variable: keyAndValue[1]))
                    }
                }
                withCredentials(credentialList) {
                    stage("common task") {
                        sh COMMON_CMD
                    }
                }
            }
            currentBuild.result = "SUCCESS"
            currentBuild.description = ""

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
