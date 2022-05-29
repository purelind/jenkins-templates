properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'GIT_BRANCH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'REPO',
                        trim: true
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'FORCE_REBUILD'
                )
        ]),
        pipelineTriggers([
            parameterizedCron('''
                H H(0-7)/4 * * * % GIT_BRANCH=feature/sprint2/nightlyrun;REPO=tiem
            ''')
        ])
])

def get_sha(repo) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${GIT_BRANCH} -s=${FILE_SERVER_URL}").trim()
}


IMAGE_TAG = GIT_BRANCH.replaceAll("/","-")


def release_one(repo) {
    def actualRepo = repo
    if (repo == "br" && GIT_BRANCH == "master") {
        actualRepo = "tidb"
    }
    if (repo == "br" && GIT_BRANCH.startsWith("release-") && GIT_BRANCH >= "release-5.2") {
        actualRepo = "tidb"
    }
    def sha1 =  get_sha(actualRepo)
    def binary = "builds/pingcap/${repo}/test/${GIT_BRANCH}/${sha1}/linux-amd64/${repo}.tar.gz"
    def paramsBuild = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: actualRepo),
        string(name: "PRODUCT", value: repo),
        string(name: "GIT_HASH", value: sha1),
        string(name: "TARGET_BRANCH", value: GIT_BRANCH),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild

    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/${repo}"
    def image = "hub-new.pingcap.net/qa/${repo}:${IMAGE_TAG}"
    if (repo == "tics") {
        image = image + ",hub-new.pingcap.net/qa/tiflash:${IMAGE_TAG}"
    }
    def paramsDocker = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "DOCKERFILE", value: dockerfile),
        string(name: "RELEASE_DOCKER_IMAGES", value: image),
    ]
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker

    if (repo == "br") {
        def dockerfileLightning = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/lightning"
        def imageLightling = "hub-new.pingcap.net/qa/tidb-lightning:${IMAGE_TAG}"
        def paramsDockerLightning = [
            string(name: "ARCH", value: "amd64"),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: binary),
            string(name: "REPO", value: "lightning"),
            string(name: "PRODUCT", value: "lightning"),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: dockerfileLightning),
            string(name: "RELEASE_DOCKER_IMAGES", value: imageLightling),
        ]
        build job: "docker-common",
                wait: true,
                parameters: paramsDockerLightning
        }
}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-cd"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest", ttyEnabled: true,
                        resourceRequestCpu: '200m', resourceRequestMemory: '1Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]     
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

stage("build") {
    run_with_pod {
        container("golang") {
            release_one(REPO)
        }
    }
}
