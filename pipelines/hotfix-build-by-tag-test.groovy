properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'REPO',
                        trim: true,
                        description: 'repo name, example tidb / tiflow / pd / tikv / tiflash / tidb-binlog',
                ),
                string(
                        defaultValue: '',
                        name: 'PRODUCT',
                        trim: true,
                        description: 'product name, example tidb / ticdc / dm / br / lightning / dumpling / tiflash / tidb-binlog',
                ),
                string(
                        defaultValue: '',
                        name: 'HOTFIX_TAG',
                        trim: true,
                        description: 'hotfix tag, example v5.1.1-20211227',
                ),
                string(
                        defaultValue: '',
                        name: 'ENTERPRISE_PLUGIN_HASH',
                        trim: true,
                        description: '',
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'PUSH_GCR'
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'PUSH_DOCKER_HUB'
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'DEBUG'
                ),
                choice(
                        name: 'EDITION',
                        choices: ['community', 'enterprise'],
                        description: 'Passing community or enterprise',
                ),
                choice(
                        name: 'ARCH',
                        choices: ['amd64', 'arm64', "both"],
                        description: 'build linux amd64 or arm64 or both',
                ),
                string(
                        defaultValue: '-1',
                        name: 'PIPELINE_BUILD_ID',
                        trim: true,
                        description: 'upload use',
                ),
        ])
])


HOTFIX_BUILD_RESULT = [:]
HOTFIX_BUILD_RESULT_FILE = "hotfix_build_result-tidb-v6.1.0-20220712.json"

env.DOCKER_HOST = "tcp://localhost:2375"
env.DOCKER_REGISTRY = "docker.io"

def run_with_pod(Closure body) {
    def label = "hotfix-build-by-tag-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-cd"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: true,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                            
                    )
            ],
            volumes: [
                            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                                    serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


try{
    run_with_pod {
    container("golang") {
        stage("hotfix") {
            echo "Test successful!"
            node("delivery"){
                container("delivery") {
                    // to be deleted
                    HOTFIX_BUILD_RESULT = [
                            "repo":"tidb",
                            "tag":"v6.1.0-20220712",
                            "results":[
                                    "tidb":[
                                            "amd64":"http://fileserver.pingcap.net/download/builds/hotfix/tidb/v6.1.0-20220712/9cf376a43f07128bdd932765ae265ab8a223df9a/centos7/tidb-linux-amd64.tar.gz",
                                            "arm64":"http://fileserver.pingcap.net/download/builds/hotfix/tidb/v6.1.0-20220712/9cf376a43f07128bdd932765ae265ab8a223df9a/centos7/tidb-linux-arm64.tar.gz",
                                            "tiup-patch-arm64":"http://fileserver.pingcap.net/download/builds/hotfix/tidb/v6.1.0-20220712/9cf376a43f07128bdd932765ae265ab8a223df9a/centos7/tidb-patch-linux-arm64.tar.gz",
                                            "tiup-patch-amd64":"http://fileserver.pingcap.net/download/builds/hotfix/tidb/v6.1.0-20220712/9cf376a43f07128bdd932765ae265ab8a223df9a/centos7/tidb-patch-linux-amd64.tar.gz",
                                            "image-arm64":"hub.pingcap.net/qa/tidb-arm64:v6.1.0-20220712",
                                            "image-amd64":"hub.pingcap.net/qa/tidb-amd64:v6.1.0-20220712",
                                            "multi-arch":"hub.pingcap.net/qa/tidb:v6.1.0-20220712",
                                            "gcrImage":"gcr.io/pingcap-public/dbaas/tidb:v6.1.0-20220712-1657640862"
                                    ]
                            ],
                            "ci_url": "https://cd.pingcap.net/job/hotfix-build-by-tag/76/display/redirect",
                            "commit_id": "9cf376a43f07128bdd932765ae265ab8a223df9a"
                    ]
                    // -----------

                    def json = groovy.json.JsonOutput.toJson(HOTFIX_BUILD_RESULT)
                    writeJSON file: "${HOTFIX_BUILD_RESULT_FILE}", json: json, pretty: 4
                    archiveArtifacts artifacts: "${HOTFIX_BUILD_RESULT_FILE}", fingerprint: true
                    echo "${HOTFIX_BUILD_RESULT_FILE}"
                    echo "${HOTFIX_BUILD_RESULT}"

                    if(fileExists("tiinsights-hotfix-builder-notify-new.py")){
                        sh "rm tiinsights-hotfix-builder-notify-new.py"
                    }

                    // to be modified   v6.1.0-20220712
                    def repo = "${params.REPO}"
                    def tag = "${params.HOTFIX_TAG}"
                    // ------
                    println "notify to feishu: ${repo} ${tag}"


                    def command = "./tidb-server -V"
                    if (repo == "tidb") {
                        command = "./tidb-server -V"
                    } else if (repo == "tiflash") {
                        command = "/tiflash/tiflash version"
                    } else if (repo == "ticdc") {
                        command = "./cdc version"
                    } else if (repo == "tikv") {
                        command = "./tikv-server -V"
                    } else if (repo == "dm") {
                        command = "./dmctl -V"
                    } else if (repo == "br") {
                        command = "./br -V"
                    } else if (repo == "lightning") {
                        command = "./tidb-lightning -V"
                    } else if (repo == "dumpling") {
                        command = "./dumpling -V"
                    } else if (repo == "tidb-binlog") {
                        command = "./binlogctl -V"
                    } else if (repo == "pd") {
                        command = "./pd-server -V"
                    } else {
                        echo "repo is : ${repo}, not exist, exit now!"
                        sh "exit 1"
                    }

                    def harbor_addr = "hub.pingcap.net/qa/${repo}:${tag}"
                    sh """
                        docker pull ${harbor_addr}
                        docker run -i --rm --entrypoint /bin/sh ${harbor_addr} -c \"${command}\" > container_info
                        cat container_info
                    """
                    def output = readFile(file: 'container_info').trim()

                    sh """
                    wget ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiinsights-hotfix-builder-notify-new.py
                    python tiinsights-hotfix-builder-notify-new.py ${HOTFIX_BUILD_RESULT_FILE}
                    cat t_text
                    """
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
}
}catch (Exception e){
    currentBuild.result = "FAILURE"
}