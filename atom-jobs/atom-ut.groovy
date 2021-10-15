properties([
    parameters([
        string(
            defaultValue: 'tiem',
            name: 'REPO',
            trim: true
        ),
        string(
            defaultValue: 'http://fileserver.pingcap.net/download/builds/pingcap/devops/cachecode/tiem/36c442509f86d58a69004059ba0ac3b74e4e4051/tiem.tar.gz',
            name: 'CACHE_CODE_FILESERVER_URL',
            trim: true
        ),
        string(
            defaultValue: '36c442509f86d58a69004059ba0ac3b74e4e4051',
            name: 'COMMIT_ID',
            trim: true,
        ),
        text(
            defaultValue: """
go test -v ./... -coverprofile=cover.out |go-junit-report > test.xml
gocov convert cover.out | gocov-xml > coverage.xml
            """,
            name: 'TEST_CMD',
            trim: true
        ),
        string(
            defaultValue: 'hub-new.pingcap.net/jenkins/centos7_golang-1.16',
            name: 'TEST_ENV',
            trim: true,
        ),
        string(
            defaultValue: 'test.xml',
            name: 'UT_REPORT_DIR',
            trim: true,
        ),
        string(
            defaultValue: 'coverage.xml',
            name: 'COV_REPORT_DIR',
            trim: true,
        ),
        string(
            defaultValue: '50',
            name: 'COVERAGE_RATE',
            trim: true,
        ),
    ])
])

def run_with_pod(Closure body) {
    def label = "atom-ut-atom-job" + UUID.randomUUID().toString()
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = 'hub-new.pingcap.net/jenkins/centos7_golang-1.16'
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '2Gi',
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


run_with_pod {
    container("golang") {
        try {
            def ws = pwd()

            stage("Download binary from fileserver") {
                sh '''
                    wget -q -c ${CACHE_CODE_FILESERVER_URL} -O - | tar -xz
                '''
            }

            stage("Test") {
                dir("${ws}/${REPO}") {
                    sh '''
                        go get -u github.com/jstemmer/go-junit-report
                        go get github.com/axw/gocov/gocov
                        go get github.com/AlekSi/gocov-xml
                    '''
                    sh TEST_CMD
                }
            }
        }  
        catch (err) {
            throw err
        } finally {
            junit testResults: "${REPO}/${UT_REPORT_DIR}"
            if (currentBuild.result == 'UNSTABLE') {
                currentBuild.result = 'FAILURE'
            }
            echo currentBuild.result
            cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: "${REPO}/${COV_REPORT_DIR}", lineCoverageTargets: "${COVERAGE_RATE}, ${COVERAGE_RATE}, ${COVERAGE_RATE}", maxNumberOfBuilds: 10, onlyStable: false, sourceEncoding: 'ASCII', zoomCoverageChart: false
            echo currentBuild.result
        }
    }
}
