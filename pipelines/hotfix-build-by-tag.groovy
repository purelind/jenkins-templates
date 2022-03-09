


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
                        defaultValue: 'tiflow',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: 'v5.1.1-20211227',
                        name: 'HOTFIX_TAG',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                ),
    ])
])


buildPathMap = [
    "tidb": 'go/src/github.com/pingcap/tidb',
    "tiflow": 'go/src/github.com/pingcap/tiflow',
    "pd": 'go/src/github.com/tikv/pd',
    "tikv": 'tikv',

]

repoUrlMap = [
    "tidb": "git@github.com:pingcap/tidb.git",
    "tiflow": "git@github.com:pingcap/tiflow.git",
    "pd": "git@github.com:tikv/pd.git",
    "tikv": "git@github.com:tikv/tikv.git",
]

tiupPatchBinaryMap = [
    "tidb": "tidb-server",
    "tikv": "tikv-server",
    "ticdc": "cdc",
    "dm": "dm-master,dm-worker,dmctl",
    "pd": "pd-server",
]



GIT_HASH = ""
HARBOR_PROJECT_PREFIX = "hub.pingcap.net/hotfix"

HOTFIX_BUILD_RESULT_FILE = "hotfix_build_result-${REPO}-${HOTFIX_TAG}.json"
HOTFIX_BUILD_RESULT = [:]
HOTFIX_BUILD_RESULT["repo"] = REPO

buildMap = [:]

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
            idleMinutes: 10,
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
                            resourceRequestCpu: '200m', resourceRequestMemory: '1Gi',
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
            def repoDailyCache = "/nfs/cache/git/src-${REPO}.tar.gz"
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
            println "checkout code ${repo} ${tag} ${githHash}"
        }
    }
}

def buildTiupPatch(originalFile, packageName, patchFile, arch) {
    if (packageName in ["tikv", "tidb", "pd", "ticdc"]) {
        HOTFIX_BUILD_RESULT["results"][packageName]["tiup-patch-amd64"] = patchFile
        println "build tiup patch for ${packageName}"
        run_with_lightweight_pod {
            container("golang") {
                deleteDir()
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
    def paramsBuild = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: product),
        string(name: "GIT_HASH", value: hash),
        string(name: "RELEASE_TAG", value: tag),
        [$class: 'BooleanParameterValue', name: 'NEED_SOURCE_CODE', value: true],
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild

    def originalFilePath = "${FILE_SERVER_URL}/download/${binary}"
    def patchFilePath = "${FILE_SERVER_URL}/download/builds/hotfix/${product}/${tag}/${GIT_HASH}/centos7/${product}-patch-linux-${arch}.tar.gz"
    buildTiupPatch("${FILE_SERVER_URL}/download/${binary}", product, patchFilePath, arch)
    

    def hotfixImageName = "${HARBOR_PROJECT_PREFIX}/${repo}:${tag}"
    if (arch == "arm64") {
        hotfixImageName = "${HARBOR_PROJECT_PREFIX}/${repo}-arm64:${tag}"
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


def buildBinaryByTag(repo, tag) {
    if (repo == "tidb") {
        println "HOTFIX_BUILD_RESULT=${HOTFIX_BUILD_RESULT}"
        HOTFIX_BUILD_RESULT["repo"] = "tidb"
        HOTFIX_BUILD_RESULT["tag"] = "${tag}"
        HOTFIX_BUILD_RESULT["results"] = [:]
        println "HOTFIX_BUILD_RESULT=${HOTFIX_BUILD_RESULT}"
        def builds = [:]
        def needBuildBr = false
        def needBuildDumpling = false
        if (tag >= "v5.2.0") {
            needBuildBr = true
        }
        if (tag >= "v5.3.0") {
            needBuildDumpling = true
        }
        if (needBuildBr) {
            def brAmd64Binary = "builds/hotfix/br/${tag}/${GIT_HASH}/centos7/br-linux-amd64.tar.gz"
            def brArm64Binary = "builds/hotfix/br/${tag}/${GIT_HASH}/centos7/br-linux-arm64.tar.gz"
            HOTFIX_BUILD_RESULT["results"]["br"] = [
                "amd64": "${FILE_SERVER_URL}/download/${brAmd64Binary}",
                "arm64": "${FILE_SERVER_URL}/download/${brArm64Binary}",
            ]
            builds["br-amd64"] = {
                buildOne(repo, "br", GIT_HASH, "amd64", brAmd64Binary, tag)
            }
            builds["br-arm64"] = {
                buildOne(repo, "br", GIT_HASH, "arm64", brArm64Binary, tag)
            }
        }
        if (needBuildDumpling) {
            def dumplingAmd64Binary = "builds/hotfix/dumpling/${tag}/${GIT_HASH}/centos7/dumpling-linux-amd64.tar.gz"
            def dumplingArm64Binary = "builds/hotfix/dumpling/${tag}/${GIT_HASH}/centos7/dumpling-linux-arm64.tar.gz"
            HOTFIX_BUILD_RESULT["results"]["dumpling"] = [
                "amd64": "${FILE_SERVER_URL}/download/${dumplingAmd64Binary}",
                "arm64": "${FILE_SERVER_URL}/download/${dumplingArm64Binary}",
            ]
            builds["dumpling-amd64"] = {
                buildOne(repo, "dumpling", GIT_HASH, "amd64", dumplingAmd64Binary, tag) 
            }
            builds["dumpling-arm64"] = {
                buildOne(repo, "dumpling", GIT_HASH, "arm64", dumplingArm64Binary, tag)
            }
        }
        def tidbAmd64Binary = "builds/hotfix/tidb/${tag}/${GIT_HASH}/centos7/tidb-linux-amd64.tar.gz"
        def tidbArm64Binary = "builds/hotfix/tidb/${tag}/${GIT_HASH}/centos7/tidb-linux-arm64.tar.gz"
        HOTFIX_BUILD_RESULT["results"]["tidb"] = [
            "amd64": "${FILE_SERVER_URL}/download/${tidbAmd64Binary}",
            "arm64": "${FILE_SERVER_URL}/download/${tidbArm64Binary}",
        ]
        builds["tidb-amd64"] = {
            buildOne(repo, "tidb", GIT_HASH, "amd64", tidbAmd64Binary, tag)
        }
        builds["tidb-arm64"] = {
            buildOne(repo, "tidb", GIT_HASH, "arm64", tidbArm64Binary, tag)
        }

        parallel builds

        println "build hotfix success"
        println "build result: ${HOTFIX_BUILD_RESULT}"
        HOTFIX_BUILD_RESULT["ci_url"] = "${RUN_DISPLAY_URL}"
        HOTFIX_BUILD_RESULT["commit_id"] = "${GIT_HASH}"
        def json = groovy.json.JsonOutput.toJson(HOTFIX_BUILD_RESULT)
        writeJSON file: "${HOTFIX_BUILD_RESULT_FILE}", json: json, pretty: 4
        archiveArtifacts artifacts: "${HOTFIX_BUILD_RESULT_FILE}", fingerprint: true
    }
    if (repo == "pd") {
        HOTFIX_BUILD_RESULT["repo"] = "pd"
        HOTFIX_BUILD_RESULT["tag"] = "${tag}"
        HOTFIX_BUILD_RESULT["results"] = [:]
        def packageName = "pd"
        def builds = [:]
        def amd64Binary = "builds/hotfix/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-amd64.tar.gz"
        def arm64Binary = "builds/hotfix/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-arm64.tar.gz"
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

        parallel builds

        println "build hotfix success"
        println "build result: ${HOTFIX_BUILD_RESULT}"
        HOTFIX_BUILD_RESULT["ci_url"] = "${RUN_DISPLAY_URL}"
        HOTFIX_BUILD_RESULT["commit_id"] = "${GIT_HASH}"
        def json = groovy.json.JsonOutput.toJson(HOTFIX_BUILD_RESULT)
        writeJSON file: "${HOTFIX_BUILD_RESULT_FILE}", json: json, pretty: 4
        archiveArtifacts artifacts: "${HOTFIX_BUILD_RESULT_FILE}", fingerprint: true
    }

    if (repo == "tikv") {
        HOTFIX_BUILD_RESULT["repo"] = "tikv"
        HOTFIX_BUILD_RESULT["tag"] = "${tag}"
        HOTFIX_BUILD_RESULT["results"] = [:]
        def packageName = "tikv"
        def builds = [:]
        def amd64Binary = "builds/hotfix/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-amd64.tar.gz"
        def arm64Binary = "builds/hotfix/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-arm64.tar.gz"
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

        parallel builds

        println "build hotfix success"
        println "build result: ${HOTFIX_BUILD_RESULT}"
        HOTFIX_BUILD_RESULT["ci_url"] = "${RUN_DISPLAY_URL}"
        HOTFIX_BUILD_RESULT["commit_id"] = "${GIT_HASH}"
        def json = groovy.json.JsonOutput.toJson(HOTFIX_BUILD_RESULT)
        writeJSON file: "${HOTFIX_BUILD_RESULT_FILE}", json: json, pretty: 4
        archiveArtifacts artifacts: "${HOTFIX_BUILD_RESULT_FILE}", fingerprint: true
    }

    if (repo == "tiflow") {
        def packageName = "ticdc"
        HOTFIX_BUILD_RESULT["repo"] = "tiflow"
        HOTFIX_BUILD_RESULT["tag"] = "${tag}"
        HOTFIX_BUILD_RESULT["results"] = [:]
        // TODO build-common and docker-common support dm (repo tiflow version >= v5.3.0)
        // def needBuildDm = false
        // build dm binary
        // build dm docker image

        // default build ticdc binary and image
        def builds = [:]
        def amd64Binary = "builds/hotfix/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-amd64.tar.gz"
        def arm64Binary = "builds/hotfix/${packageName}/${tag}/${GIT_HASH}/centos7/${packageName}-linux-arm64.tar.gz"
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

        parallel builds

        println "build hotfix success"
        println "build result: ${HOTFIX_BUILD_RESULT}"
        HOTFIX_BUILD_RESULT["ci_url"] = "${RUN_DISPLAY_URL}"
        HOTFIX_BUILD_RESULT["commit_id"] = "${GIT_HASH}"
        def json = groovy.json.JsonOutput.toJson(HOTFIX_BUILD_RESULT)
        writeJSON file: "${HOTFIX_BUILD_RESULT_FILE}", json: json, pretty: 4
        archiveArtifacts artifacts: "${HOTFIX_BUILD_RESULT_FILE}", fingerprint: true
    }

    currentBuild.description = "hotfix build ${repo} ${tag}"
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
            if (!validHotfixTag(HOTFIX_TAG)) {
                println "invalid hotfix tag ${HOTFIX_TAG}"
                exit 1
            }
            def ws = pwd()
            dir("${REPO}") {
                checkOutCode(REPO, HOTFIX_TAG)
                buildBinaryByTag(REPO, HOTFIX_TAG)

                notifyToFeishu(HOTFIX_BUILD_RESULT_FILE)
            }
        }
    }
}