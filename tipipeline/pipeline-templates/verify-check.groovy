properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'PIPELINE_YAML',
                        trim: true
                ),
        ])
])

common = {}
commitID = ""
taskStartTimeInMillis = System.currentTimeMillis()

node("lightweight_pod") {
    container("golang") {
        checkout scm
        common = load "tipipeline/common.groovy"
        // sh """
        // wget http://fileserver.pingcap.net/download/cicd/debug/common.groovy
        // """
        // common = load "common.groovy"

        pipelineSpec = common.loadPipelineConfig(PIPELINE_YAML)
        commitID = ghprbActualCommit
        println "commitID: ${commitID}"
        if (commitID == "" || commitID == null) {
            throw "commitID is empty"
        }
        common.runPipeline(pipelineSpec, "verify", ghprbTargetBranch, commitID, ghprbPullId)
    }
}