


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

properties([
        parameters([
                string(
                        defaultValue: 'tidb',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: 'v5.4.0-20220223',
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
]

repoUrlMap = [
    "tidb": "git@github.com:pingcap/tidb.git",
    "tiflow": "git@github.com:pingcap/tiflow.git"
]

GIT_HASH = ""
HARBOR_PROJECT_PREFIX = "hub.pingcap.net/wulifu"

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
        // TODO: 确认 build-common 是否可以不传入 TARGET_BRANCH
        // string(name: "TARGET_BRANCH", value: ""),
        string(name: "RELEASE_TAG", value: tag),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild

    def hotfixImageName = "${HARBOR_PROJECT_PREFIX}/${repo}:${tag}"
    if (arch == "arm64") {
        hotfixImageName = "${HARBOR_PROJECT_PREFIX}/${repo}-arm64:${tag}"
    }
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

            builds["dumpling-amd64"] = {
                buildOne(repo, "dumpling", GIT_HASH, "amd64", dumplingAmd64Binary, tag) 
            }
            builds["dumpling-arm64"] = {
                buildOne(repo, "dumpling", GIT_HASH, "arm64", dumplingArm64Binary, tag)
            }
        }
        def tidbAmd64Binary = "builds/hotfix/tidb/${tag}/${GIT_HASH}/centos7/tidb-linux-amd64.tar.gz"
        def tidbArm64Binary = "builds/hotfix/tidb/${tag}/${GIT_HASH}/centos7/tidb-linux-arm64.tar.gz"
        builds["tidb-amd64"] = {
            buildOne(repo, "tidb", GIT_HASH, "amd64", tidbAmd64Binary, tag)
        }
        builds["tidb-arm64"] = {
            buildOne(repo, "tidb", GIT_HASH, "arm64", tidbArm64Binary, tag)
        }

        parallel builds
    }
}

def notifyToFeishu(repo, tag) {
    println "notify to feishu: ${repo} ${tag}"
}


run_with_pod {
    container("golang") {
        stage("hotfix-${REPO}") {
            if (!validHotfixTag(HOTFIX_TAG)) {
                println "invalid hotfix tag ${tag}"
                exit 1
            }
            def ws = pwd()
            dir("${repo}") {
                checkOutCode(REPO, HOTFIX_TAG)
                buildBinaryByTag(REPO, HOTFIX_TAG)
            }
        }
    }
}