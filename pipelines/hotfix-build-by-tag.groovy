


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


import java.text.SimpleDateFormat

def date = new Date()
ts13 = date.getTime() / 1000
ts10 = (Long) ts13
sdf = new SimpleDateFormat("yyyyMMdd")
day = sdf.format(date)
begin_time = new Date().format('yyyy-MM-dd HH:mm:ss')

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
ENTERPRISE_PLUGIN_HASH = ""
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
                            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                                    serverPath: '/data/nvme1n1/nfs/git', readOnly: false)
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
                            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                                    serverPath: '/data/nvme1n1/nfs/git', readOnly: false)
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
        HOTFIX_BUILD_RESULT["results"][packageName]["tiup-patch-${arch}"] = "${FILE_SERVER_URL}/download/${patchFile}"  
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

def get_enterprise_plugin_hash(tidb_tag) {
    hotifxBranchReg = /^(v)?(\d+\.\d+)(\.\d+\-.+)?/
    enterprise_plugin_branch = String.format('release-%s', (tidb_tag =~ hotifxBranchReg)[0][2])
    println "enterprise_plugin_branch: ${enterprise_plugin_branch}"
    print("The enterprise plugin branch is ${enterprise_plugin_branch}")
    dir("enterprise-plugin") {
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
        ENTERPRISE_PLUGIN_HASH = sh(returnStdout: true, script: "python gethash.py -repo=enterprise-plugin -version=${enterprise_plugin_branch}").trim()
        if (ENTERPRISE_PLUGIN_HASH.length() == 40) {
            println "valid commit hash: ${ENTERPRISE_PLUGIN_HASH}"
        } else {
            println "invalid commit hash: ${ENTERPRISE_PLUGIN_HASH}"
            currentBuild.result = "FAILURE"
            throw new Exception("invalid commit hash: ${ENTERPRISE_PLUGIN_HASH}, Throw to stop pipeline")
        }
    }
    // checkout([$class: 'GitSCM',
    //           branches: [[name: "${enterprise_plugin_branch}"]],
    //           extensions: [[$class: 'RelativeTargetDirectory',
    //                         relativeTargetDir: 'enterprise-plugin']],
    //           userRemoteConfigs: [[credentialsId: 'github-sre-bot',
    //                                url: 'https://github.com/pingcap/enterprise-plugin.git']]])
    // dir("enterprise-plugin") {
    //     ENTERPRISE_PLUGIN_HASH = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
    //     println "enterprise_plugin_hash: ${ENTERPRISE_PLUGIN_HASH}" 
    // }
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
    println "params: ${paramsBuild}"
    def buildsbinary = [:]
    buildsbinary["build-${arch}-${params_product}"] = {
        build job: "build-common",
            wait: true,
            parameters: paramsBuild
    }

    def plugin_output_binary = "builds/hotfix/enterprise-plugin/${tag}/${ENTERPRISE_PLUGIN_HASH}/centos7/enterprise-plugin-linux-${arch}.tar.gz"
    if (params.DEBUG) { 
        plugin_output_binary = "builds/hotfix-debug/enterprise-plugin/${tag}/${ENTERPRISE_PLUGIN_HASH}/centos7/enterprise-plugin-linux-${arch}.tar.gz"
    }

    if (product == "tidb" && EDITION == "enterprise") {
        println "build tidb enterprise edition binary"
        HOTFIX_BUILD_RESULT["results"][product]["enterprise-plugin"] = "${FILE_SERVER_URL}/download/${plugin_output_binary}"

        def paramsBuildPlugin = [
            string(name: "ARCH", value: arch),
            string(name: "OS", value: "linux"),
            string(name: "EDITION", value: EDITION),
            string(name: "OUTPUT_BINARY", value: plugin_output_binary),
            string(name: "REPO", value: "enterprise-plugin"),
            string(name: "PRODUCT", value: "enterprise-plugin"),
            string(name: "GIT_HASH", value: ENTERPRISE_PLUGIN_HASH),
            string(name: "TIDB_HASH", value: GIT_HASH),
            string(name: "RELEASE_TAG", value: tag),
            [$class: 'BooleanParameterValue', name: 'NEED_SOURCE_CODE', value: false],
            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
        ]
        println "params: ${paramsBuildPlugin}"
        buildsbinary["build-${arch}-enterprise-plugin"] = {
            build job: "build-common",
                wait: true,
                parameters: paramsBuildPlugin
        }

    }

    parallel buildsbinary

    def originalFilePath = "${FILE_SERVER_URL}/download/${binary}"
    def patchFilePath = "builds/hotfix/${product}/${tag}/${GIT_HASH}/centos7/${product}-patch-linux-${arch}.tar.gz"
    if (params.DEBUG) {
          patchFilePath = "builds/hotfix-debug/${product}/${tag}/${GIT_HASH}/centos7/${product}-patch-linux-${arch}.tar.gz"  
    }
    buildTiupPatch("${FILE_SERVER_URL}/download/${binary}", product, patchFilePath, arch)
    

    def hotfixImageName = "${HARBOR_PROJECT_PREFIX}/${product}:${tag}"
    // if arch is both, we need to build multi-arch image
    // we build arm64 and amd64 image first, then manfest them and push to harbor
    if (params.ARCH == "both" ) {
        hotfixImageName = "${HARBOR_PROJECT_PREFIX}/${product}-${arch}:${tag}"
    }
    if (arch == "arm64") {
        hotfixImageName = "${HARBOR_PROJECT_PREFIX}/${product}-arm64:${tag}"
    }
    if (params.DEBUG) {
        hotfixImageName = "${hotfixImageName}-debug"
    }
    HOTFIX_BUILD_RESULT["results"][product]["image-${arch}"] = hotfixImageName
    println "build hotfix image ${hotfixImageName}"
    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
    if (product == "tidb" && EDITION == "enterprise") { 
        dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/enterprise/${product}"
    }
    def INPUT_BINARYS = binary
    if (product == "tidb" && EDITION == "enterprise") {
        INPUT_BINARYS = "${binary},${plugin_output_binary}"
    }
    def paramsDocker = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: INPUT_BINARYS),
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


def pushImageToGCR(harborImage, repo, product, tag) {
    // 命名规范：
    //- vX.Y.Z-yyyymmdd-<timestamp>，举例：v6.1.0-20220524-1654851973
    def imageTag = "${tag}-${ts10}"
    def gcrImage = "gcr.io/pingcap-public/dbaas/${product}:${imageTag}"
    def default_params = [
        string(name: 'SOURCE_IMAGE', value: harborImage),
        string(name: 'TARGET_IMAGE', value: gcrImage),
    ]
    println "sync image ${harborImage} to ${gcrImage}"
    build(job: "jenkins-image-syncer",
            parameters: default_params,
            wait: true)
    HOTFIX_BUILD_RESULT["results"][product]["gcrImage"] = gcrImage
}

def pushImageToDockerhub(harborImage, repo, product, tag) {
    // 命名规范：
    //- vX.Y.Z-yyyymmdd，举例：v6.1.0-20220524
    def imageTag = "${tag}"
    def dockerHubImage = "pingcap/${product}:${imageTag}"
    def default_params = [
        string(name: 'SOURCE_IMAGE', value: harborImage),
        string(name: 'TARGET_IMAGE', value: dockerHubImage),
    ]
    println "sync image ${harborImage} to ${dockerHubImage}"
    build(job: "jenkins-image-syncer",
            parameters: default_params,
            wait: true)
    HOTFIX_BUILD_RESULT["results"][product]["dockerHubImage"] = dockerHubImage
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

    if (params.ARCH == "both") {
        def amd64Image = HOTFIX_BUILD_RESULT["results"][product]["image-amd64"]
        def arm64Image = HOTFIX_BUILD_RESULT["results"][product]["image-arm64"]
        def manifestImage = "${HARBOR_PROJECT_PREFIX}/${packageName}:${tag}"
        HOTFIX_BUILD_RESULT["results"][product]["multi-arch"] = manifestImage
        if (params.DEBUG) {
            manifestImage = "${manifestImage}-debug"
        }
        def paramsManifest = [
            string(name: "AMD64_IMAGE", value: amd64Image),
            string(name: "ARM64_IMAGE", value: arm64Image),
            string(name: "MULTI_ARCH_IMAGE", value: manifestImage),
        ]
        stage("build manifest image") {
            build job: "manifest-multiarch-common",
                wait: true,
                parameters: paramsManifest
        }
        
        // all image push gcr are multiarch images
        // only push image to gcr when not debug
        if (!params.DEBUG.toBoolean() && params.PUSH_GCR.toBoolean()) {
            stage("push image gcr") {
                pushImageToGCR(manifestImage, repo, packageName, tag)  
            }
        }
    }

    if (!params.DEBUG.toBoolean() && params.PUSH_DOCKER_HUB.toBoolean()) {
        // only push image to dockerhub when not debug
        def dockerHubImage = "${HARBOR_PROJECT_PREFIX}/${packageName}:${tag}"
        stage("push image dockerhub") {
            pushImageToDockerhub(dockerHubImage, repo, packageName, tag)
        }
    }

    println "build hotfix success"
    println "build result: ${HOTFIX_BUILD_RESULT}"
    HOTFIX_BUILD_RESULT["ci_url"] = "${RUN_DISPLAY_URL}"
    HOTFIX_BUILD_RESULT["commit_id"] = "${GIT_HASH}"
    def json = groovy.json.JsonOutput.toJson(HOTFIX_BUILD_RESULT)
    writeJSON file: "${HOTFIX_BUILD_RESULT_FILE}", json: json, pretty: 4
    archiveArtifacts artifacts: "${HOTFIX_BUILD_RESULT_FILE}", fingerprint: true

    currentBuild.description = "hotfix build ${repo} ${tag} ${GIT_HASH}"
}

def notifyToFeishu(buildResultFile) {
    println "notify to feishu: ${REPO} ${HOTFIX_TAG}"
    sh """
    wget ${FILE_SERVER_URL}/download/rd-index-agent/hotfix_builder_notify/tiinsights-hotfix-builder-notify.py
    python3 tiinsights-hotfix-builder-notify.py ${buildResultFile}
    """
}

def notifyToFeishuNew(buildResultFile) {
    echo "Test successful!"
    node("delivery"){
        container("delivery") {
            def json = groovy.json.JsonOutput.toJson(HOTFIX_BUILD_RESULT)
            echo "${HOTFIX_BUILD_RESULT}"
            writeJSON file: "${HOTFIX_BUILD_RESULT_FILE}", json: json, pretty: 4
            archiveArtifacts artifacts: "${HOTFIX_BUILD_RESULT_FILE}", fingerprint: true
            echo "${HOTFIX_BUILD_RESULT_FILE}"
            echo "${HOTFIX_BUILD_RESULT}"

            if(fileExists("tiinsights-hotfix-builder-notify-new.py")){
                sh "rm tiinsights-hotfix-builder-notify-new.py"
            }

            // to be modified   v6.1.0-20220712
            def repo = "${params.REPO}"
            def product = "${params.PRODUCT}"
            def tag = "${params.HOTFIX_TAG}"
            // ------
            println "notify to feishu: ${repo} ${tag}"

            def command = "./tidb-server -V"
            if (product == "tidb") {
                command = "./tidb-server -V"
            } else if (product == "tiflash") {
                command = "/tiflash/tiflash version"
            } else if (product == "ticdc") {
                command = "./cdc version"
            } else if (product == "tikv") {
                command = "./tikv-server -V"
            } else if (product == "dm") {
                command = "./dmctl -V"
            } else if (product == "br") {
                command = "./br -V"
            } else if (product == "lightning") {
                command = "./tidb-lightning -V"
            } else if (product == "dumpling") {
                command = "./dumpling -V"
            } else if (product == "tidb-binlog") {
                command = "./binlogctl -V"
            } else if (product == "pd") {
                command = "./pd-server -V"
            } else {
                echo "repo is : ${repo}, product is : ${product}, not exist, exit now!"
                sh "exit 1"
            }

            def harbor_addr = "hub.pingcap.net/qa/${product}:${tag}"
            sh """
                        docker pull ${harbor_addr}
                        docker run -i --rm --entrypoint /bin/sh ${harbor_addr} -c \"${command}\" > container_info
                        cat container_info
                    """
            sh """
                    wget ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiinsights-hotfix-builder-notify-new.py
                    python tiinsights-hotfix-builder-notify-new.py ${HOTFIX_BUILD_RESULT_FILE} ${command}
                    cat t_text
                    """
        }
    }
}

def upload_result_to_db() {
    pipeline_build_id= params.PIPELINE_BUILD_ID
    pipeline_id= "12"
    pipeline_name= "Hotfix-Build"
    status= currentBuild.result
    build_number= BUILD_NUMBER
    job_name= JOB_NAME
    artifact_meta= params.PRODUCT + " commit:" + GIT_HASH
    begin_time= begin_time
    end_time= new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by= "sre-bot"
    component= params.PRODUCT
    arch= params.ARCH
    artifact_type= params.EDITION
    branch= "None"
    version= params.HOTFIX_TAG
    build_type= "hotfix-build"
    if (params.PUSH_GCR == "true") {
        push_gcr = "Yes"
    }else{
        push_gcr = "No"
    }

    build job: 'upload_result_to_db',
            wait: true,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_BUILD_ID', value: pipeline_build_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_ID', value: pipeline_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value:  pipeline_name],
                    [$class: 'StringParameterValue', name: 'STATUS', value:  status],
                    [$class: 'StringParameterValue', name: 'BUILD_NUMBER', value:  build_number],
                    [$class: 'StringParameterValue', name: 'JOB_NAME', value:  job_name],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_META', value: artifact_meta],
                    [$class: 'StringParameterValue', name: 'BEGIN_TIME', value: begin_time],
                    [$class: 'StringParameterValue', name: 'END_TIME', value:  end_time],
                    [$class: 'StringParameterValue', name: 'TRIGGERED_BY', value:  triggered_by],
                    [$class: 'StringParameterValue', name: 'COMPONENT', value: component],
                    [$class: 'StringParameterValue', name: 'ARCH', value:  arch],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_TYPE', value:  artifact_type],
                    [$class: 'StringParameterValue', name: 'BRANCH', value: branch],
                    [$class: 'StringParameterValue', name: 'VERSION', value: version],
                    [$class: 'StringParameterValue', name: 'BUILD_TYPE', value: build_type],
                    [$class: 'StringParameterValue', name: 'PUSH_GCR', value: push_gcr]
            ]

}

def testImageWithBasicSql={HOTFIX_TAG, PRODUCT ->
    if (PRODUCT in ["tidb", "tikv", "pd", "tiflash"]){
        build job: 'check-images-with-basic-sql',
                wait: false,
                parameters: [
                        string( name: 'hotfixVersion', value: HOTFIX_TAG),
                        string( name: 'component', value: PRODUCT)
                ]
    }
}

// TODO
// verify the build result: binary and docker image
// def verifyBuildResult() {
env.DOCKER_HOST = "tcp://localhost:2375"
env.DOCKER_REGISTRY = "docker.io"

try{
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
                if (PRODUCT == "tidb" && EDITION == "enterprise" )  {
                    println "enterprise tidb, need to checkout enterprise code then build plugin"
                    get_enterprise_plugin_hash(HOTFIX_TAG)
                }
                buildByTag(REPO, HOTFIX_TAG, PRODUCT)
                testImageWithBasicSql(HOTFIX_TAG, PRODUCT)

                notifyToFeishuNew(HOTFIX_BUILD_RESULT_FILE)
            }
        }
    }
    }
    currentBuild.result = "SUCCESS"
}catch (Exception e){
    currentBuild.result = "FAILURE"
    println "${e}"
    throw new Exception("${e}")
}finally{
    upload_result_to_db()
}
