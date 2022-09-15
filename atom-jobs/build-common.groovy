

/*
* @OUTPUT_BINARY(string:binary url on fileserver, transfer througth atom jobs,Required)
* @REPO(string:repo name,eg tidb, Required)
* @PRODUCT(string:product name,eg tidb-ctl,if not set,default was the same as repo name, Optional)
* @ARCH(enumerate:arm64,amd64,Required)
* @OS(enumerate:linux,darwin,Required)
* @GIT_HASH(string:to get correct code from github,Required)
* @GIT_PR(string:generate ref head to pre get code from pr,Optional)
* @RELEASE_TAG(string:for release workflow,what tag to release,Optional)
* @TARGET_BRANCH(string:for daily CI workflow,Optional)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
* @FAILPOINT(bool:build failpoint binary or not,only for tidb,tikv,pd now ,default false,Optional)
* @EDITION(enumerate:,community,enterprise,Required)
* @USE_TIFLASH_RUST_CACHE(string:use rust code cache, for tiflash only, Optional)
*/

properties([
        parameters([
                choice(choices: ['arm64', 'amd64'],name: 'ARCH'),
                choice(choices: ['linux', 'darwin'],name: 'OS'),
                choice(choices: ['community', 'enterprise'],name: 'EDITION'),
                string(
                        defaultValue: 'builds/debug-to-delete/tidb/62480cf19b4d7f6821f82c9cee272c096e9fec52/centos7/tidb-linux-amd64.tar.gz',
                        name: 'OUTPUT_BINARY',
                        trim: true
                ),
                string(
                        defaultValue: 'tidb',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: 'tidb',
                        name: 'PRODUCT',
                        trim: true,
                ),
                string(
                        defaultValue: '62480cf19b4d7f6821f82c9cee272c096e9fec52',
                        name: 'GIT_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'GIT_PR',
                        trim: true
                ),
                string(
                        defaultValue: 'v6.3.0-alpha',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
                string(
                        defaultValue: 'master',
                        name: 'TARGET_BRANCH', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'USE_TIFLASH_RUST_CACHE',
                        trim: true                        
                ),
                booleanParam(name: 'FORCE_REBUILD', defaultValue: true),
                booleanParam(name: 'FAILPOINT',defaultValue: false),
                booleanParam(name: 'NEED_SOURCE_CODE',defaultValue: false),
                booleanParam(name: 'TIFLASH_DEBUG',defaultValue: false),
    ])
])


// check if binary already has been built. 
def ifFileCacheExists() {
    if (params.FORCE_REBUILD){
        return false
    } 
    result = sh(script: "curl -I ${FILE_SERVER_URL}/download/${OUTPUT_BINARY} -X \"HEAD\"|grep \"200 OK\"", returnStatus: true)
    // result equal 0 mean cache file exists
    if (result == 0) {
        echo "file ${FILE_SERVER_URL}/download/${OUTPUT_BINARY} found in cache server,skip build again"
        return true
    }
    return false
}

def get_repo_git_ssh_url(repo) {
    def url = "git@github.com:pingcap/${repo}.git"
    if (repo == "tikv" || repo == "importer" || repo == "pd") {
        url = "git@github.com:tikv/${repo}.git"
    }
    if (repo == "tiem") {
        url = "git@github.com:pingcap-inc/${repo}.git"
    }
    return url
}

def get_golang_build_pod_params(goversion, arch) {
    def pod_params = [:]
    def pod_image = ""
    def request_cpu = "4000m"
    def request_memory = "8Gi"
    def jnlp_image = "jenkins/inbound-agent:4.11-1-jdk11"
    def namespace = "jenkins-cd"
    def cluster_name = "kubernetes"

    if (arch == "arm64") {
        cluster_name = "kubernetes-arm64"
        jnlp_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
        switch(goversion) {
            case "go1.13":
                pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.13-arm64:latest"
                break
            case "go1.16":
                pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
                break 
            case "go1.18":
                pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.18.5-arm64:latest"
                break
            case "go1.19":
                pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.19-arm64:latest"
                break
            default:
                println "Unknown go version: ${goversion}"
                throw new Exception("Unknown go version: ${goversion}")         
        }
    } else {
        switch(goversion) {
            case "go1.13":
                pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:cached"
                break
            case "go1.16":
                pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
                break 
            case "go1.18": 
                pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.18.5:latest"
                break
            case "go1.19":
                pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.19:latest"
                break
            default:
                println "Unknown go version: ${goversion}"
                throw new Exception("Unknown go version: ${goversion}")         
        }
    }

    pod_params["pod_image"] = pod_image
    pod_params["request_cpu"] = request_cpu
    pod_params["request_memory"] = request_memory
    pod_params["jnlp_image"] = jnlp_image
    pod_params["namespace"] = namespace
    pod_params["cluster_name"] = cluster_name
    return pod_params

}

def get_rust_build_pod_params(product, arch) {
    def pod_params = [:]
    def pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_rust:latest"
    def request_cpu = "8000m"
    def request_memory = "20Gi"
    def jnlp_image = "jenkins/inbound-agent:4.11-1-jdk11"
    def namespace = "jenkins-cd"
    def cluster_name = "kubernetes"

    if (arch == "arm64") {
        cluster_name = "kubernetes-arm64"
        jnlp_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
        pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_rust-arm64:latest"
    }

    pod_params["pod_image"] = pod_image
    pod_params["request_cpu"] = request_cpu
    pod_params["request_memory"] = request_memory
    pod_params["jnlp_image"] = jnlp_image
    pod_params["namespace"] = namespace
    pod_params["cluster_name"] = cluster_name
    return pod_params
}


// linux amd64
// normal : hub.pingcap.net/tiflash/tiflash-builder:latest
// llvm : hub.pingcap.net/tiflash/tiflash-llvm-base:amd64
// llvm org : hub.pingcap.net/tiflash/tiflash-llvm-base:amd64-llvmorg-14.0.6
// linux arm64
// normal : hub.pingcap.net/tiflash/tiflash-builder:arm64
// llvm : hub.pingcap.net/tiflash/tiflash-llvm-base:aarch64
// llvm org : hub.pingcap.net/tiflash/tiflash-llvm-base:aarch64-llvmorg-14.0.6
def get_tiflash_image(os, arch) {
    def image_map = [
        "linux-amd64": "hub.pingcap.net/tiflash/tiflash-builder:latest",
        "linux-amd64-llvm": "hub.pingcap.net/tiflash/tiflash-llvm-base:amd64",
        "linux-amd64-llvmorg": "hub.pingcap.net/tiflash/tiflash-llvm-base:amd64-llvmorg-14.0.6",
        "linux-arm64": "hub.pingcap.net/tiflash/tiflash-builder:arm64",
        "linux-arm64-llvm": "hub.pingcap.net/tiflash/tiflash-llvm-base:aarch64",
        "linux-arm64-llvmorg": "hub.pingcap.net/tiflash/tiflash-llvm-base:aarch64-llvmorg-14.0.6"
    ]
        
    def image_type = "${os}-${arch}"
    if (fileExists('release-centos7-llvm/scripts/build-release.sh') && params.OS != "darwin") {
        image_type = "${os}-${arch}-llvm"
        def image_tag_suffix = ""
        if (fileExists(".toolchain.yml")) {
            def config = readYaml(file: ".toolchain.yml")
            image_tag_suffix = config.image_tag_suffix
            if (image_tag_suffix == "llvmorg.14.0.6") {
                image_type = "${os}-${arch}-llvmorg"
            } else {
                println "unknown image_tag_suffix: ${image_tag_suffix}"
                println "current support build image : ${image_map}"
                throw new Error("unknown image_tag_suffix: ${image_tag_suffix}")
            }
        }
    }
    
    return image_map[image_type]  
}

def get_tiflash_build_pod_params(arch) {
        def pod_params = [:]
        def pod_image = get_tiflash_image("linux", arch)
        def request_cpu = "10000m"
        def request_memory = "20Gi"
        def jnlp_image = "jenkins/inbound-agent:4.11-1-jdk11"
        def namespace = "jenkins-cd"
        def cluster_name = "kubernetes"

        if (arch == "arm64") {
            cluster_name = "kubernetes-arm64"
            jnlp_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
        }

        pod_params["pod_image"] = pod_image
        pod_params["request_cpu"] = request_cpu
        pod_params["request_memory"] = request_memory
        pod_params["jnlp_image"] = jnlp_image
        pod_params["namespace"] = namespace
        pod_params["cluster_name"] = cluster_name
        return pod_params
}

def use_baremetal_agent(os) {
    if (os == "darwin") {
        return true
    }
    return false
}

def get_baremetal_agent_node(os, arch, product) {
    def node_label = "mac"
    if(os == "darwin" && arch == "arm64") {
        node_label = "mac-arm"
    }
    if (os == "darwin" && arch == "amd64") {
        node_label = "mac"
    }
    if (os == "darwin" && arch == "arm64" && product in ["tics", "tiflash"]) {
        node_label = "mac-arm-tiflash"
    }
    return node_label
}

def set_go_bin_path(goversion) {
    def go_bin_path = ""
    switch (goversion) {
        case "go1.13":
            go_bin_path = "/usr/local/go/bin"
            break
        case "go1.16":
            go_bin_path = "/usr/local/go1.16.4/bin"
            break
        case "go1.18":
            go_bin_path = "/usr/local/go1.18.5/bin"
            break
        case "go1.19":
            go_bin_path = "/usr/local/go1.19/bin"
            break
        default:
            println "Unknown go version: ${goversion}"
            throw new Exception("Unknown go version: ${goversion}")         
    }
    return go_bin_path
}

def set_bin_path(go_bin_path, os, arch) {
    def bin_path = ""
    if (os == "darwin" && arch == "amd64") {
        bin_path = "/opt/homebrew/bin:/opt/homebrew/sbin:/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${go_bin_path}:/usr/local/opt/binutils/bin/"
    }
    if (os == "darwin" && arch == "arm64") {
        bin_path = "/opt/homebrew/bin:/opt/homebrew/sbin:/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${go_bin_path}:/usr/local/opt/binutils/bin/"
    }
    return bin_path
}

def checkoutCode() {
    def repoDailyCache = "/nfs/cache/git/src-${REPO}.tar.gz"
    if (fileExists(repoDailyCache)) {
        println "get code from nfs to reduce clone time"
        sh """
        cp -R ${repoDailyCache}  ./
        tar -xzf ${repoDailyCache} --strip-components=1
        rm -f src-${REPO}.tar.gz
        """
        sh "chown -R 1000:1000 ./"
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
            sh "chown -R 1000:1000 ./"
        } else {
            println "get code from github"
        }
    }
    def specRef = "+refs/heads/*:refs/remotes/origin/*"
    if (params.GIT_PR.length() >= 1) {
    specRef = "+refs/pull/${GIT_PR}/*:refs/remotes/origin/pr/${GIT_PR}/*"
    }
    def repo_git_url = "git@github.com:pingcap/${REPO}.git"
    if (REPO == "tikv" || REPO == "importer" || REPO == "pd") {
        repo_git_url = "git@github.com:tikv/${REPO}.git"
    }
    if (REPO == "tiem") {
        repo_git_url = "git@github.com:pingcap-inc/${REPO}.git"
    }
    println "specRef: ${specRef}"
    sh "git version"
    checkout changelog: false, poll: true,
                    scm: [$class: 'GitSCM', branches: [[name: "${GIT_HASH}"]], doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CheckoutOption', timeout: 30],
                                    [$class: 'CloneOption', timeout: 60],
                                    [$class: 'PruneStaleBranch'],
                                    [$class: 'SubmoduleOption', timeout: 30, disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''],
                                    [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                            refspec      : specRef,
                                            url          : repo_git_url]]]
}

// choose which go version to use. 
def String select_go_version(String tag,String branch) {
    goVersion="go1.19"
    if (tag.startsWith("v") && tag <= "v5.1") {
        return "go1.13"
    }
    if (tag.startsWith("v") && tag > "v5.1" && tag < "v6.0") {
        return "go1.16"
    }
    if (tag.startsWith("v") && tag >= "v6.0" && tag < "v6.3") {
        return "go1.18"
    }
    if (branch.startsWith("release-") && branch < "release-5.1"){
        return "go1.13"
    }
    if (branch.startsWith("release-") && branch >= "release-5.1" && branch < "release-6.0"){
        return "go1.16"
    }
    if (branch.startsWith("release-") && branch >= "release-6.0" && branch < "release-6.3"){
        return "go1.18"
    }
    if (branch.startsWith("hz-poc") || branch.startsWith("arm-dup") ) {
        return "go1.16"
    }
    if (REPO == "tiem") {
        return "go1.16"
    }
    return "go1.19"
}

def package_binary() {
    // 是否和代码一起打包，可以手动设置 NEED_SOURCE_CODE=true
    if (params.NEED_SOURCE_CODE) {
        sh """
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
        """
    //  pd,tidb,tidb-test 非release版本，和代码一起打包
    } else if ((PRODUCT == "pd" || PRODUCT == "tidb" || PRODUCT == "tidb-test" ) && RELEASE_TAG.length() < 1) {
        sh """
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
        """
    } else if (PRODUCT == "tiem") {
        sh """
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
        """
    } else {
        sh """
        cd ${TARGET}
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
        """
    }
}

def start_work() {
    checkoutCode()
    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
        compileStartTimeInMillis = System.currentTimeMillis()
        sh buildsh[product]
        compileFinishTimeInMillis = System.currentTimeMillis()
    }
    uploadStartTimeInMillis = System.currentTimeMillis()
    package_binary()
    uploadFinishTimeInMillis = System.currentTimeMillis()
}

goVersion = select_go_version(params.RELEASE_TAG,params.TARGET_BRANCH)
goBinPath = set_go_bin_path(goVersion)
println "go bin path: ${goBinPath}"
if (goBinPath == "") {
    println "go version ${goVersion} not found"
    throw new Exception("go version ${goVersion} not found")
}
def binPath = set_bin_path(goBinPath, params.OS, params.ARCH)
println "binPath: ${binPath}"


product_go_array = ["tidb", "pd", "tidb-binlog", "tidb-tools", "ticdc", "dm", "br", "dumpling", "ng-monitoring", "tidb-enterprise-tools", "monitoring", "tidb-test", "enterprise-plugin"]
product_rust_array = []

// define build script here.
TARGET = "output" 
buildsh = [:]
buildsh["tidb-ctl"] = """
if [[${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
go build -o binarys/${PRODUCT}
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp binarys/${PRODUCT} ${TARGET}/bin/            
"""

buildsh["tidb"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ "${EDITION}" = 'enterprise' ]; then
    export TIDB_EDITION=Enterprise
fi;
if [ "${OS}" = 'darwin' ]; then
    export PATH=${binPath}
fi;
go version
make clean
git checkout .
if [ ${failpoint} == 'true' ]; then
    make failpoint-enable
fi;
if [ ${OS} == 'linux' ]; then
    WITH_RACE=1 make && mv bin/tidb-server bin/tidb-server-race
    git checkout .
    WITH_CHECK=1 make && mv bin/tidb-server bin/tidb-server-check
    git checkout .
    make failpoint-enable && make server && mv bin/tidb-server{,-failpoint} && make failpoint-disable
    git checkout .
    make server_coverage || true
    git checkout .
    if [ \$(grep -E '^ddltest:' Makefile) ]; then
        git checkout .
        make ddltest
    fi
        
    if [ \$(grep -E '^importer:' Makefile) ]; then
        git checkout .
        make importer
    fi
fi;
if [ ${failpoint} == 'true' ]; then
    make failpoint-enable
fi;
make 
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp binarys/tidb-ctl ${TARGET}/bin/ || true
cp bin/* ${TARGET}/bin/ 
"""

buildsh["enterprise-plugin"] = """
if [ "${OS}" == 'darwin' ]; then
    export PATH=${binPath}
fi;
go version
cd ../
rm -rf tidb
curl -O ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz
tar -xf src-tidb.tar.gz
cd tidb
git fetch --all
git reset --hard ${TIDB_HASH}
cd cmd/pluginpkg
go build 
cd ../../../enterprise-plugin
cd whitelist
go mod tidy
cd ..
../tidb/cmd/pluginpkg/pluginpkg -pkg-dir whitelist -out-dir whitelist
md5sum whitelist/whitelist-1.so > whitelist/whitelist-1.so.md5
cd audit
go mod tidy
cd ..
../tidb/cmd/pluginpkg/pluginpkg -pkg-dir audit -out-dir audit
md5sum audit/audit-1.so > audit/audit-1.so.md5
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp whitelist/whitelist-1.so.md5 ${TARGET}/bin
cp whitelist/whitelist-1.so ${TARGET}/bin
cp audit/audit-1.so.md5 ${TARGET}/bin
cp audit/audit-1.so ${TARGET}/bin
"""

buildsh["tidb-binlog"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
make clean
git checkout .
make
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["pd"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
git checkout .
if [ ${EDITION} == 'enterprise' ]; then
    export PD_EDITION=Enterprise
fi;
if [ ${failpoint} == 'true' ]; then
    make failpoint-enable
fi;
make
make tools
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tidb-tools"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
make clean
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["ticdc"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

// only support dm version >= 5.3.0 (dm in repo tiflow)
// start from 6.0.0, dm use webui is supported
dmUseWebUI = "true"
if ((params.RELEASE_TAG.startsWith("release-") && params.RELEASE_TAG <"release-6.0") || (params.RELEASE_TAG.startsWith("v") && params.RELEASE_TAG <"v6.0.0")) { 
    dmUseWebUI = "false"
}
dmNodePackage = "node-v16.14.0-linux-x64"
if (params.OS == "linux" && params.ARCH == "arm64") {
    dmNodePackage = "node-v16.14.0-linux-arm64"
} else if (params.OS == "darwin" && params.ARCH == "arm64") {
    dmNodePackage = "node-v16.14.0-darwin-arm64"
} else if (params.OS == "darwin" && params.ARCH == "amd64") {
    dmNodePackage = "node-v16.14.0-darwin-x64"
} else {
    dmNodePackage = "node-v16.14.0-linux-x64"
}

buildsh["dm"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
if [ ${dmUseWebUI} == "true" ]; then
    wget http://fileserver.pingcap.net/download/ee-tools/${dmNodePackage}.tar.gz
    tar -xvf ${dmNodePackage}.tar.gz
    export PATH=\$(pwd)/${dmNodePackage}/bin:\$PATH
    node -v
    npm install -g yarn
    make dm-master-with-webui dm-worker dmctl dm-syncer
else
    make dm
fi;
ls -alh bin/
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
mkdir -p ${TARGET}/conf  
if [[ -d "dm/dm" ]]; then
    mv dm/dm/master/task_basic.yaml ${TARGET}/conf/
    mv dm/dm/master/task_advanced.yaml ${TARGET}/conf/
    mv dm/dm/master/dm-master.toml ${TARGET}/conf/
    mv dm/dm/worker/dm-worker.toml ${TARGET}/conf/
else
    mv dm/master/task_basic.yaml ${TARGET}/conf/
    mv dm/master/task_advanced.yaml ${TARGET}/conf/
    mv dm/master/dm-master.toml ${TARGET}/conf/
    mv dm/worker/dm-worker.toml ${TARGET}/conf/
fi;
mv LICENSE ${TARGET}/
# start from v6.0.0(include v6.0.0), dm-ansible is removed, link https://github.com/pingcap/tiflow/pull/4917
# dm-master and dm-worker tiup package also need those config file even for version >=6.0.0
#  1. dm-master/conf/dm_worker.rules.yml
#  2. dm-master/scripts/DM-Monitor-Professional.json
#  3. dm-master/scripts/DM-Monitor-Standard.json
if [[ -d "dm/dm/dm-ansible" ]]; then
    mkdir -p ${TARGET}/dm-ansible
    cp -r dm/dm/dm-ansible/* ${TARGET}/dm-ansible/
else
    mkdir -p ${TARGET}/dm-ansible
    mkdir -p ${TARGET}/dm-ansible/conf
    mkdir -p ${TARGET}/dm-ansible/scripts
    cp -r dm/metrics/alertmanager/dm_worker.rules.yml ${TARGET}/dm-ansible/conf
    cp -r dm/metrics/grafana/* ${TARGET}/dm-ansible/scripts
fi;
# start from v6.0.0(include v6.0.0), pingcap/dm-monitor-initializer is replace by pingcap/monitoring
# link https://github.com/pingcap/monitoring/pull/188.
if [[ -d "dm/dm/dm-ansible" ]]; then
    # mkdir -p ${TARGET}/monitoring/dashboards
    # mkdir -p ${TARGET}/monitoring/rules
    cd dm
    cp -f dm/dm-ansible/scripts/DM-Monitor-Professional.json monitoring/dashboards/
    cp -f dm/dm-ansible/scripts/DM-Monitor-Standard.json monitoring/dashboards/
    cp -f dm/dm-ansible/scripts/dm_instances.json monitoring/dashboards/
    mkdir -p monitoring/rules
    cp -f dm/dm-ansible/conf/dm_worker.rules.yml monitoring/rules/
    cd monitoring && go run dashboards/dashboard.go && cd ..
    cd ..
    mv dm/monitoring ${TARGET}/
fi;
if [[ ${ARCH} == "amd64" ]]; then
    curl http://download.pingcap.org/mydumper-latest-linux-amd64.tar.gz | tar xz
    mv mydumper-latest-linux-amd64/bin/mydumper bin/ && rm -rf mydumper-latest-linux-amd64
fi;
cp bin/* ${TARGET}/bin/
"""

buildsh["br"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
if [ ${failpoint} == 'true' ]; then
    make failpoint-enable
fi;
if [ ${REPO} == "tidb" ]; then
    make build_tools
else
    make build
fi;
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["dumpling"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
if [ ${REPO} == "tidb" ]; then
    make build_dumpling
else
    make build
fi;
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["ng-monitoring"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
make
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/  
"""

buildsh["tidb-enterprise-tools"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
make syncer
make loader
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin    
cp bin/* ${TARGET}/bin/   
"""

buildsh["tics"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIFLASH_EDITION=Enterprise
fi;
if [ ${OS} == 'darwin' ]; then
    if [ ${ARCH} == "arm64" ]; then
        cd ..
        cp -f /Users/pingcap/birdstorm/fix-poco.sh ./
        cp -f /Users/pingcap/birdstorm/fix-libdaemon.sh ./
        ./fix-poco.sh
        ./fix-libdaemon.sh
        cd tics
    fi
    export PROTOC=/usr/local/bin/protoc
    export PATH=/usr/local/opt/binutils/bin:/usr/local/bin:/Users/pingcap/.cargo/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${goBinPath}
    mkdir -p release-darwin/build/
    [ -f "release-darwin/build/build-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-release.sh > release-darwin/build/build-release.sh
    [ -f "release-darwin/build/build-cluster-manager.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-cluster-manager.sh > release-darwin/build/build-cluster-manager.sh
    [ -f "release-darwin/build/build-tiflash-proxy.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-proxy.sh > release-darwin/build/build-tiflash-proxy.sh
    [ -f "release-darwin/build/build-tiflash-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-release.sh > release-darwin/build/build-tiflash-release.sh
    chmod +x release-darwin/build/*
    ./release-darwin/build/build-release.sh
    ls -l ./release-darwin/tiflash/
    mv release-darwin ${TARGET}
else
    if [ "${params.USE_TIFLASH_RUST_CACHE}" == "true" ]; then
        mkdir -p ~/.cargo/registry
        mkdir -p ~/.cargo/git
        mkdir -p /rust/registry/cache
        mkdir -p /rust/registry/index 
        mkdir -p /rust/git/db
        mkdir -p /rust/git/checkouts
        
        rm -rf ~/.cargo/registry/cache && ln -s /rust/registry/cache ~/.cargo/registry/cache
        rm -rf ~/.cargo/registry/index && ln -s /rust/registry/index ~/.cargo/registry/index 
        rm -rf ~/.cargo/git/db && ln -s /rust/git/db ~/.cargo/git/db
        rm -rf ~/.cargo/git/checkouts && ln -s /rust/git/checkouts ~/.cargo/git/checkouts
    fi
    # check if LLVM toolchain is provided
    echo "the new parameter of tiflash debug is : ${params.TIFLASH_DEBUG}"
    
    if [[ -d "release-centos7-llvm" && \$(which clang 2>/dev/null) ]]; then
        if [[ "${params.TIFLASH_DEBUG}" != 'true' ]]; then
            echo "start release ..........."
            NPROC=12 release-centos7-llvm/scripts/build-release.sh
        else
            echo "start debug ..........."
            NPROC=12 release-centos7-llvm/scripts/build-debug.sh
        fi
        mkdir -p ${TARGET}
        mv release-centos7-llvm/tiflash ${TARGET}/tiflash
    else
        if [[ "${params.TIFLASH_DEBUG}" != 'true' ]]; then
            echo "start release ..........."
            NPROC=12 release-centos7/build/build-release.sh
        else
            echo "start debug ..........."
            NPROC=12 release-centos7/build/build-debug.sh
        fi
        mkdir -p ${TARGET}
        mv release-centos7/tiflash ${TARGET}/tiflash
    fi
fi
rm -rf ${TARGET}/build-release || true
"""

buildsh["tikv"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIKV_EDITION=Enterprise
    export ROCKSDB_SYS_SSE=0
fi;
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
if [ ${OS} == 'linux' ]; then
    echo using gcc 8
    source /opt/rh/devtoolset-8/enable
fi;
if [ ${failpoint} == 'true' ]; then
    CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make fail_release
else
    CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make dist_release
fi;
wait
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin
"""

buildsh["importer"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
echo using gcc 8
source /opt/rh/devtoolset-8/enable
if [[ ${ARCH} == 'arm64' ]]; then
    ROCKSDB_SYS_SSE=0 make release
else
    make release
fi
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp target/release/tikv-importer ${TARGET}/bin
"""

// NOTE: remove param --auto-push for pull-monitoring 
//      we don't want to auto create pull request in repo https://github.com/pingcap/monitoring/pulls
buildsh["monitoring"] = """
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go build -o pull-monitoring  cmd/monitoring.go
./pull-monitoring  --config=monitoring.yaml --tag=${RELEASE_TAG} --token=\$TOKEN
rm -rf ${TARGET}
mkdir -p ${TARGET}
mv monitor-snapshot/${RELEASE_TAG}/operator/* ${TARGET}
"""

buildsh["tiem"] = """
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
make build
"""

buildsh["tidb-test"] = """
if [[ ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
if [ -d "partition_test/build.sh" ]; then
    cd partition_test
    bash build.sh
    cd ..
fi;
if [ -d "coprocessor_test/build.sh" ]; then
    cd coprocessor_test
    bash build.sh
    cd ..
fi;
if [ -d "concurrent-sql/build.sh" ]; then
    cd concurrent-sql
    bash build.sh
    cd ..
fi;
"""

buildsh["enterprise-plugin"] = """
if [ "${OS}" == 'darwin' ]; then
    export PATH=${binPath}
fi;
go version
cd ../
rm -rf tidb
curl -O ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz
tar -xf src-tidb.tar.gz
cd tidb
git fetch --all
git reset --hard ${TIDB_HASH}
cd cmd/pluginpkg
go build 
cd ../../../enterprise-plugin
cd whitelist
go mod tidy
cd ..
../tidb/cmd/pluginpkg/pluginpkg -pkg-dir whitelist -out-dir whitelist
md5sum whitelist/whitelist-1.so > whitelist/whitelist-1.so.md5
cd audit
go mod tidy
cd ..
../tidb/cmd/pluginpkg/pluginpkg -pkg-dir audit -out-dir audit
md5sum audit/audit-1.so > audit/audit-1.so.md5
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp whitelist/whitelist-1.so.md5 ${TARGET}/bin
cp whitelist/whitelist-1.so ${TARGET}/bin
cp audit/audit-1.so.md5 ${TARGET}/bin
cp audit/audit-1.so ${TARGET}/bin
"""


podTemplateYAML = '''
apiVersion: v1
kind: Pod
spec:
  env:
    - name: NODE_IP
      valueFrom:
        fieldRef:
          fieldPath: status.hostIP
''' 


podTemplate(label: "${JOB_NAME}-${BUILD_NUMBER}", 
    namespace: "jenkins-cd", 
    cloud: "kubernetes",
    containers: [
        containerTemplate(name: 'golang', image: "hub.pingcap.net/jenkins/centos7_golang-1.19:latest", 
            ttyEnabled: true, command: '/bin/sh -c', args: 'cat',
            resourceRequestCpu: "1000m", resourceRequestMemory: "2Gi",
            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
        )
    ]){
    node("${JOB_NAME}-${BUILD_NUMBER}") {
    container("golang") {
        stage("build ${PRODUCT}-${OS}-${ARCH}") {
            if (ifFileCacheExists()) {
                return
            }
            if (use_baremetal_agent(OS)) {
                // darwin mac build use baremetal agent
                def nodeLabel = get_baremetal_agent_node(params.OS, params.ARCH, params.PRODUCT)
                println "use baremeta agent: ${nodeLabel}"
                node(nodeLabel) {
                    start_work()
                }
            } else {
                // linux build use k8s agent
                if (product in product_go_array) {
                    pod_params = get_golang_build_pod_params(goVersion, ARCH)
                    println "pod_params: ${pod_params}"
                } else if (product in ["tiflash", "tics"]) {
                    // tiflash need checkout code first to select pod image against some files
                    // so we need to checkout code before podTemplateYAML
                    checkoutCode()
                    pod_params = get_tiflash_build_pod_params(ARCH)
                } else {
                    pod_params = get_rust_build_pod_params(product, ARCH)
                }
                println "${pod_params.jnlp_image}"
                def label = "${JOB_NAME}-${PRODUCT}-${BUILD_NUMBER}"
                podTemplate(label: label, 
                    namespace: "${pod_params.namespace}", 
                    cloud: "${pod_params.cluster_name}",
                    yaml: podTemplateYAML,
                    yamlMergeStrategy: merge(),
                    containers: [
                        containerTemplate(name: 'jnlp', image: "${pod_params.jnlp_image}",
                            alwaysPullImage: false, resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                        ),
                        containerTemplate(name: 'build', image: "${pod_params.pod_image}", 
                            ttyEnabled: true, command: '/bin/sh -c', args: 'cat',
                            resourceRequestCpu: "${pod_params.request_cpu}",
                            resourceRequestMemory: "${pod_params.request_memory}",
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                        )
                    ], 
                    volumes: [
                        emptyDirVolume(mountPath: '/tmp', memory: false),
                        emptyDirVolume(mountPath: '/go', memory: false)
                    ]) {
                        node(label) {
                            container("build") {
                                start_work()
                            }
                        }
                }
            }
        }
    }
    }
}

