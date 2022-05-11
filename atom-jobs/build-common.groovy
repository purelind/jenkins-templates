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
                choice(
                        choices: ['arm64', 'amd64'],
                        name: 'ARCH'
                ),
                choice(
                        choices: ['linux', 'darwin'],
                        name: 'OS'
                ),
                choice(
                        choices: ['community', 'enterprise'],
                        name: 'EDITION'
                ),
                string(
                        defaultValue: '',
                        name: 'OUTPUT_BINARY',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PRODUCT',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'GIT_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'GIT_PR',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TARGET_BRANCH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_HASH',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                ),
                booleanParam(
                        name: 'FAILPOINT',
                        defaultValue: false
                ),
                booleanParam(
                        name: 'NEED_SOURCE_CODE',
                        defaultValue: false
                ),
                string(
                        defaultValue: '',
                        name: 'USE_TIFLASH_RUST_CACHE',
                        trim: true                        
                ),                
    ])
])

if (params.PRODUCT.length() <= 1) {
    PRODUCT = REPO
}

failpoint = "false"
if (params.FAILPOINT) {
    failpoint = "true"
}


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

// support branch example
//  master | hz-poc
//  relase-4.0
//  release-4.0-20210812
//  release-5.1
//  release-5.3

// choose which go version to use. 
def String needUpgradeGoVersion(String tag,String branch) {
    goVersion="go1.18"
    if (tag.startsWith("v") && tag <= "v5.1") {
        return "go1.13"
    }
    if (tag.startsWith("v") && tag > "v5.1" && tag < "v6.0") {
        return "go1.16"
    }
    if (branch.startsWith("release-") && branch < "release-5.1"){
        return "go1.13"
    }
    if (branch.startsWith("release-") && branch >= "release-5.1" && branch < "release-6.0"){
        return "go1.16"
    }
    if (branch.startsWith("hz-poc") || branch.startsWith("arm-dup") ) {
        return "go1.16"
    }
    if (REPO == "tiem") {
        return "go1.16"
    }
    return "go1.18"
}

def goBuildPod = "${GO1180_BUILD_SLAVE}"
def GO_BIN_PATH = "/usr/local/go1.18/bin"
goVersion = needUpgradeGoVersion(params.RELEASE_TAG,params.TARGET_BRANCH)
if ( goVersion == "go1.16" ) {
    goBuildPod = "${GO1160_BUILD_SLAVE}"
    GO_BIN_PATH = "/usr/local/go1.16.4/bin"
}
if ( goVersion == "go1.13" ) {
    goBuildPod = "${GO_BUILD_SLAVE}"
    GO_BIN_PATH = "/usr/local/go/bin"
}

// choose which node to use.
def nodeLabel = goBuildPod
def containerLabel = "golang"
def binPath = ""
def useArmPod = false
if (params.ARCH == "arm64" && params.PRODUCT in ["tidb", "enterprise-plugin"]) {
    useArmPod = true
}
if (params.PRODUCT == "tikv" || params.PRODUCT == "importer") {
    nodeLabel = "build"
    containerLabel = "rust"
} 
if (params.PRODUCT == "tics") {
    nodeLabel = "build_tiflash"
    containerLabel = "tiflash"
} 
if (params.ARCH == "arm64" && params.OS == "linux" && !useArmPod) {
    binPath = "/usr/local/node/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}"
    nodeLabel = "arm"
    containerLabel = ""
    if (params.PRODUCT == "tics"){
        nodeLabel = "tiflash_build_arm"
        containerLabel = "tiflash"
    }
}
if (params.OS == "darwin" && params.ARCH == "amd64") {
    binPath = "/opt/homebrew/bin:/opt/homebrew/sbin:/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}:/usr/local/opt/binutils/bin/"
    nodeLabel = "mac"
    containerLabel = ""
}

if (params.OS == "darwin" && params.ARCH == "arm64") {
    binPath = "/opt/homebrew/bin:/opt/homebrew/sbin:/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}:/usr/local/opt/binutils/bin/"
    nodeLabel = "mac-arm"
    containerLabel = ""
    if (params.PRODUCT == "tics"){
        nodeLabel = "mac-arm-tiflash"
    }
}

// define git url and git ref.
repo = "git@github.com:pingcap/${REPO}.git"
if (REPO == "tikv" || REPO == "importer" || REPO == "pd") {
    repo = "git@github.com:tikv/${REPO}.git"
}
if (REPO == "tiem") {
    repo = "git@github.com:pingcap-inc/${REPO}.git"
}
specRef = "+refs/heads/*:refs/remotes/origin/*"
if (params.GIT_PR.length() >= 1) {
   specRef = "+refs/pull/${GIT_PR}/*:refs/remotes/origin/pr/${GIT_PR}/*"
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
        } else {
            println "get code from github"
        }
    }
    checkout changelog: false, poll: true,
                    scm: [$class: 'GitSCM', branches: [[name: "${GIT_HASH}"]], doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CheckoutOption', timeout: 30],
                                    [$class: 'CloneOption', timeout: 60],
                                    [$class: 'PruneStaleBranch'],
                                    [$class: 'SubmoduleOption', timeout: 30, disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''],
                                    [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                            refspec      : specRef,
                                            url          : repo]]]
    sh "git checkout ${GIT_HASH}"
}


// define build script here.
TARGET = "output" 
buildsh = [:]
buildsh["tidb-ctl"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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

buildsh["tidb-binlog"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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

mv dm/dm/master/task_basic.yaml ${TARGET}/conf/
mv dm/dm/master/task_advanced.yaml ${TARGET}/conf/
mv dm/dm/master/dm-master.toml ${TARGET}/conf/
mv dm/dm/worker/dm-worker.toml ${TARGET}/conf/
mv LICENSE ${TARGET}/

# start from v6.0.0(include v6.0.0), dm-ansible is removed, link https://github.com/pingcap/tiflow/pull/4917
if [[ -d "dm/dm/dm-ansible" ]]; then
    mkdir -p ${TARGET}/dm-ansible
    cp -r dm/dm/dm-ansible/* ${TARGET}/dm-ansible/
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
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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
    export PATH=/usr/local/opt/binutils/bin:/usr/local/bin:/Users/pingcap/.cargo/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${GO_BIN_PATH}
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
    if [[ -d "release-centos7-llvm" && \$(which clang 2>/dev/null) ]]
    then
        NPROC=12 release-centos7-llvm/scripts/build-release.sh
        mkdir -p ${TARGET}
        mv release-centos7-llvm/tiflash ${TARGET}/tiflash
    else
        NPROC=12 release-centos7/build/build-release.sh
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
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go build -o pull-monitoring  cmd/monitoring.go
./pull-monitoring  --config=monitoring.yaml --tag=${RELEASE_TAG} --token=\$TOKEN
rm -rf ${TARGET}
mkdir -p ${TARGET}
mv monitor-snapshot/${RELEASE_TAG}/operator/* ${TARGET}
"""

buildsh["tiem"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
    export PATH=${binPath}
fi;
go version
make build
"""

buildsh["tidb-test"] = """
if [[ ${ARCH} == 'arm64' ||  ${OS} == 'darwin' ]]; then
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

def packageBinary() {
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

def release(product, label) {
    // if has built,skip build.
    if (ifFileCacheExists()) {
        return
    }

    checkoutCode()

    if (PRODUCT == 'tics') {
        if (fileExists('release-centos7-llvm/scripts/build-release.sh') && params.OS != "darwin") {
            label = "tiflash-llvm"
        }
    }

    if (label != '') {
        container(label) {
            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                sh buildsh[product]
            }
            packageBinary()
        }
    } else {
        withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
            sh buildsh[product]
        }
        packageBinary()
    }
}

def run_with_arm_go_pod(Closure body) {
    switch(goVersion) {
        case "go1.13":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.13-arm64:latest"
            break
        case "go1.16":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            break
        case "go1.18":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.18-arm64:latest"
            break
        default:
            println "invalid go version ${goVersion}"
            break
    }
    def cloud = "kubernetes-arm64"
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def namespace = "jenkins-cd"
    def jnlp_docker_image = "hub.pingcap.net/jenkins/jnlp-slave-arm64:latest"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${arm_go_pod_image}", ttyEnabled: true,
                            resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                            
                    ),
                    containerTemplate(
                        name: 'jnlp', image: jnlp_docker_image, alwaysPullImage: false,
                        resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
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

stage("Build ${PRODUCT}") {
    if (params.PRODUCT in ["tidb", "enterprise-plugin"] && params.ARCH == "arm64") {
        run_with_arm_go_pod{
            dir("go/src/github.com/pingcap/${PRODUCT}") {
                deleteDir()
                release(PRODUCT, containerLabel)
            }
        }
    } else {
        node(nodeLabel) {
            dir("go/src/github.com/pingcap/${PRODUCT}") {
                deleteDir()
                release(PRODUCT, containerLabel)
            }
        }
    }
}
