


// build binary and image for hotfix build by tag
// tag format: v5.3.1-20210221
// repo: tidb / tikv / pd / tiflow / tiflash
//  -- tidb: include tidb / br / dumpling / lightning
//  -- tiflow include dm and ticdc


/*

buildBinaryByTag() (amd64 && arm64)
buildImageByTag()  (amd64 && arm64) (hub.pingcap.net & docker.io)
notify to feishu


*/

// test params
// tidb  v5.4.0-20220223
// tiflow v5.1.1-20211227
// pd v5.0.4-20211208
// tikv v5.3.0-20220107

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
                )
    ])
])


buildPathMap = [
    "tidb": 'go/src/github.com/pingcap/tidb',
    "tiflow": 'go/src/github.com/pingcap/tiflow',
    "pd": 'go/src/github.com/tikv/pd',
    "tikv": 'go/src/github.com/tikv/tikv',
    "tiflash": 'src/github.com/pingcap/tiflash',
    "tidb-binlog": 'go/src/github.com/pingcap/tidb-binlog',
]

repoUrlMap = [
    "tidb": "git@github.com:pingcap/tidb.git",
    "tiflow": "git@github.com:pingcap/tiflow.git",
    "pd": "git@github.com:tikv/pd.git",
    "tikv": "git@github.com:tikv/tikv.git",
    "tiflash": "git@github.com:pingcap/tiflash.git",
    "tidb-binlog": "git@github.com:pingcap/tidb-binlog.git"
]

tiupPatchBinaryMap = [
    "tidb": "tidb-server",
    "tikv": "tikv-server",
    "ticdc": "cdc",
    "dm": "dm-master,dm-worker,dmctl",
    "pd": "pd-server",
    "tiflash": "",
    "tidb-binlog": "pump,drainer,reparo,binlogctl"
]



GIT_HASH = ""
HARBOR_PROJECT_PREFIX = "hub.pingcap.net/qa"
if (params.DEBUG) {
    println "DEBUG mode"
    HARBOR_PROJECT_PREFIX = "hub.pingcap.net/ee-debug"
} else {
    println "NOT DEBUG mode"
}

HOTFIX_BUILD_RESULT_FILE = "hotfix_build_result-${REPO}-${HOTFIX_TAG}.json"
HOTFIX_BUILD_RESULT = [:]
HOTFIX_BUILD_RESULT["repo"] = REPO

buildMap = [:]


def get_sha() {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${REPO} -version=${HOTFIX_TAG}").trim()
}

def debugEnv() {
    stage("debug env") {
        echo("env")
        echo("REPO: ${REPO}")
        echo("PRODUCT: ${PRODUCT}")
        echo("HOTFIX_TAG: ${HOTFIX_TAG}")
        echo("FORCE_REBUILD: ${FORCE_REBUILD}")
        echo("EDITION: ${EDITION}")
        echo("HARBOR_PROJECT_PREFIX: ${HARBOR_PROJECT_PREFIX}")
    }
}


// tag example : v5.3.1-20210221
def selectImageGoVersion(repo, tag) {
    def originalTag = tag.substring(1)
    if (repo in ["tidb", "pd", "tiflow"]) {
       println "selectImageGoVersion: " + repo + " " + originalTag
       if (originalTag >= "v5.2.0") {
           println "repo ${repo} with tag ${originalTag} is use go1.16"
           return "imagego1.16.0"
       } else {
           println "repo ${repo} with tag ${originalTag} is use go1.13"
           return "imagego1.13.7"
       }
    } else {
        println "repo {$repo} not a golang repo, return a default go1.16 image"
        return "imagego1.16.0"
    }
}


// tag example : v5.3.1-20210221 is a hotfix build
// tag version example : v5.3.1 is not a hotfix tag
def validHotfixTag(tag) {
    if (tag.startsWith("v") && tag.contains("-202")) {
        return true
    } else {
        return false
    }
}

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

def run_with_lightweight_pod(Closure body) {
    def label = "hotfix-build-by-tag-light-${BUILD_NUMBER}"
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
                            resourceRequestCpu: '1000m', resourceRequestMemory: '1Gi',
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

def checkOutCode(repo, tag) {
    stage("checkout code") {
        def buildPath = buildPathMap[repo]
        def refspec = "+refs/tags/${tag}:refs/tags/${tag}"
        def repoUrl = repoUrlMap[repo]
        dir(buildPath){
            def repoDailyCache = "/home/jenkins/agent/ci-cached-code-daily/src-${REPO}.tar.gz"
            if (fileExists(repoDailyCache)) {
                println "get code from nfs to reduce clone time"
                sh """
                cp -R ${repoDailyCache}  ./
                tar -xzf ${repoDailyCache} --strip-components=1
                rm -f src-${REPO}.tar.gz
                """
            } else {
                def codeCacheInFileserverUrl = "${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-${REPO}.tar.gz"
                def cacheExisted = sh(returnStatus: true, script: """
                    if curl --output /dev/null --silent --head --fail ${codeCacheInFileserverUrl}; then exit 0; else exit 1; fi
                    """)
                if (cacheExisted == 0) {
                    println "get code from fileserver to reduce clone time"
                    println "codeCacheInFileserverUrl=${codeCacheInFileserverUrl}"
                    sh """
                    curl -O ${codeCacheInFileserverUrl}
                    tar -xzf src-${REPO}.tar.gz --strip-components=1
                    rm -f src-${REPO}.tar.gz
                    """
                } else {
                    println "get code from github"
                }
            }
            retry(3){
                checkout changelog: false,
                poll: true,
                scm: [
                        $class: 'GitSCM',
                        branches: [[name: "refs/tags/${tag}"]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CheckoutOption', timeout: 30],
                                    [$class: 'LocalBranch'],
                                    [$class: 'CloneOption', noTags: true, timeout: 60]],
                        submoduleCfg: [],
                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                            refspec: refspec,
                                            url: repoUrl]]
                ]
            }
            def githHash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            GIT_HASH = githHash
            if (GIT_HASH.length() == 40) {
                println "valid commit hash: ${GIT_HASH}"
            } else {
                println "invalid commit hash: ${GIT_HASH}"
                currentBuild.result = "FAILURE"
                throw new Exception("invalid commit hash: ${GIT_HASH}, Throw to stop pipeline")
            }
            println "checkout code ${repo} ${tag} ${githHash}"
        }
    }
}


def buildTiupPatch(originalFile, packageName, patchFile, arch) {
    if (packageName in ["tikv", "tidb", "pd", "ticdc"]) {
        HOTFIX_BUILD_RESULT["results"][packageName]["tiup-patch-amd64"] = "${FILE_SERVER_URL}/download/${patchFile}"  
        println "build tiup patch for ${packageName}"
        run_with_lightweight_pod {
            container("golang") {
                def patchBinary = tiupPatchBinaryMap[packageName]
                println "build ${packageName} tiup patch: ${patchBinary} ${patchFile}"

                sh """
                curl ${originalFile} | tar -xz bin/
                ls -alh bin/
                cp bin/${patchBinary} .
                tar -cvzf ${patchBinary}-linux-${arch}.tar.gz ${patchBinary}
                curl -F ${patchFile}=@${patchBinary}-linux-${arch}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }
    } else {
        println "skip build tiup patch for ${packageName}"
    }

}

def buildOne(repo, product, hash, arch, binary, tag) {
    println "build binary ${repo} ${product} ${hash} ${arch}"
    println "binary: ${binary}"
    needSourceCode = false
    if (product in ["tidb", "tikv", "pd"]) {
        needSourceCode = true
    }
    def params_repo = repo
    def params_product = product
    if (product == "tiflash") {
        params_product = "tics"
        params_repo = "tics"
    }
    def paramsBuild = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: EDITION),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: params_repo),
        string(name: "PRODUCT", value: params_product),
        string(name: "GIT_HASH", value: hash),
        string(name: "RELEASE_TAG", value: tag),
        [$class: 'BooleanParameterValue', name: 'NEED_SOURCE_CODE', value: needSourceCode],
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild

    def originalFilePath = "${FILE_SERVER_URL}/download/${binary}"
    def patchFilePath = "builds/hotfix/${product}/${tag}/${GIT_HASH}/centos7/${product}-patch-linux-${arch}.tar.gz"
    if (params.DEBUG) {
          patchFilePath = "builds/hotfix-debug/${product}/${tag}/${GIT_HASH}/centos7/${product}-patch-linux-${arch}.tar.gz"  
    }
    buildTiupPatch("${FILE_SERVER_URL}/download/${binary}", product, patchFilePath, arch)
    

    def hotfixImageName = "${HARBOR_PROJECT_PREFIX}/${product}:${tag}"
    if (arch == "arm64") {
        hotfixImageName = "${HARBOR_PROJECT_PREFIX}/${product}-arm64:${tag}"
    }
    if (params.DEBUG) {
        hotfixImageName = "${hotfixImageName}-debug"
    }
    HOTFIX_BUILD_RESULT["results"][product]["image"] = hotfixImageName
    println "build hotfix image ${hotfixImageName}"
    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
    def paramsDocker = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: binary),
        string(name: "REPO", value: product),
        string(name: "PRODUCT", value: product),
        string(name: "RELEASE_TAG", value: tag),
        string(name: "DOCKERFILE", value: dockerfile),
        string(name: "RELEASE_DOCKER_IMAGES", value: hotfixImageName),
    ]
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker
}


def buildByTag(repo, tag, packageName) {
    HOTFIX_BUILD_RESULT["repo"] = repo
    HOTFIX_BUILD_RESULT["tag"] = tag
    HOTFIX_BUILD_RESULT["results"] = [:]
    def builds = [:]
    def amd64Binary = "builds/hotfix/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-amd64.tar.gz"
    def arm64Binary = "builds/hotfix/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-arm64.tar.gz"
    if (params.DEBUG) {
        amd64Binary = "builds/hotfix-debug/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-amd64.tar.gz"
        arm64Binary = "builds/hotfix-debug/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-arm64.tar.gz"
    }
    switch(ARCH) {
        case "amd64":
            HOTFIX_BUILD_RESULT["results"]["${packageName}"] = [
                "amd64": "${FILE_SERVER_URL}/download/${amd64Binary}",
            ]
            builds["${packageName}-${ARCH}"] = { 
                buildOne(repo, packageName, GIT_HASH, "amd64", amd64Binary, tag)
            }
            break
        case "arm64":
            HOTFIX_BUILD_RESULT["results"]["${packageName}"] = [
                "arm64": "${FILE_SERVER_URL}/download/${arm64Binary}",
            ]
            builds["${packageName}-${ARCH}"] = {  
                buildOne(repo, packageName, GIT_HASH, "arm64", arm64Binary, tag)
            }
            break
        case "both":
            HOTFIX_BUILD_RESULT["results"]["${packageName}"] = [
                "amd64": "${FILE_SERVER_URL}/download/${amd64Binary}",
                "arm64": "${FILE_SERVER_URL}/download/${arm64Binary}",
            ]
            builds["${packageName}-amd64"] = {  
                buildOne(repo, packageName, GIT_HASH, "amd64", amd64Binary, tag)
            }
            builds["${packageName}-arm64"] = {  
                buildOne(repo, packageName, GIT_HASH, "arm64", arm64Binary, tag)
            }
            break
        default:
            println "unknown arch ${ARCH}"
            throw new Exception("unknown arch ${ARCH}")
        break
    }
    parallel builds

    println "build hotfix success"
    println "build result: ${HOTFIX_BUILD_RESULT}"
    HOTFIX_BUILD_RESULT["ci_url"] = "${RUN_DISPLAY_URL}"
    HOTFIX_BUILD_RESULT["commit_id"] = "${GIT_HASH}"
    def json = groovy.json.JsonOutput.toJson(HOTFIX_BUILD_RESULT)
    writeJSON file: "${HOTFIX_BUILD_RESULT_FILE}", json: json, pretty: 4
    archiveArtifacts artifacts: "${HOTFIX_BUILD_RESULT_FILE}", fingerprint: true

    currentBuild.description = "hotfix build ${repo} ${tag} ${GIT_HASH}"
    // currentBuild.description += "\n"
}

def notifyToFeishu(buildResultFile) {
    println "notify to feishu: ${REPO} ${HOTFIX_TAG}"
    sh """
    wget ${FILE_SERVER_URL}/download/rd-index-agent/hotfix_builder_notify/tiinsights-hotfix-builder-notify.py
    python3 tiinsights-hotfix-builder-notify.py ${buildResultFile}
    """
}

// TODO
// verify the build result: binary and docker image
// def verifyBuildResult() {


run_with_pod {
    container("golang") {
        stage("hotfix-${REPO}") {
            // TODO enable valid hotfix tag
            // if (!validHotfixTag(HOTFIX_TAG)) {
            //     println "invalid hotfix tag ${HOTFIX_TAG}"
            //     throw new Exception("invalid hotfix tag ${HOTFIX_TAG}")
            // }
            def ws = pwd()
            dir("${REPO}") {
                // checkOutCode(REPO, HOTFIX_TAG)
                GIT_HASH = get_sha()
                if (GIT_HASH.length() == 40) {
                    println "valid commit hash: ${GIT_HASH}"
                } else {
                    println "invalid commit hash: ${GIT_HASH}"
                    currentBuild.result = "FAILURE"
                    throw new Exception("invalid commit hash: ${GIT_HASH}, Throw to stop pipeline")
                }
                println "checkout code ${REPO} ${HOTFIX_TAG} ${GIT_HASH}"
                buildByTag(REPO, HOTFIX_TAG, PRODUCT)

                notifyToFeishu(HOTFIX_BUILD_RESULT_FILE)
            }
        }
    }
}