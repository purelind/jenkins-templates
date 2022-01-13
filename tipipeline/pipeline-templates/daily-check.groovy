// properties([
//         parameters([
//                 string(
//                         defaultValue: '',
//                         name: 'PIPELINE_YAML',
//                         trim: true
//                 ),
//         ])
// ])

def get_sha(repo,branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

common = {}
commitID = ""
// PipelineSpec pipelineSpec =  new PipelineSpec()
node("${GO1160_BUILD_SLAVE}") {
    container("golang") {
        // checkout scm
        // common = load "tipipeline/common.groovy"

        // sh """
        // wget https://raw.githubusercontent.com/purelind/jenkins-templates/purelind/refactor-init-test-fix/tipipeline/common.groovy
        // """
        sh """
        wget http://fileserver.pingcap.net/download/cicd/debug/common.groovy
        """
        common = load "common.groovy"

        pipelineSpec = common.loadPipelineConfig(PIPELINE_YAML)
        commitID = get_sha(pipelineSpec.repo,pipelineSpec.defaultRef)
    }
}

// pipelineSpec = comloadPipelineConfig(PIPELINE_YAML)
common.runPipeline(pipelineSpec, "daily", pipelineSpec.defaultRef, String commitID, "")