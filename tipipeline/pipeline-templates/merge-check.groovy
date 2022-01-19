// properties([
//         parameters([
//                 string(
//                         defaultValue: '',
//                         name: 'PIPELINE_YAML',
//                         trim: true
//                 ),
//         ])
// ])

BRANCH = REF
if (REF.startsWith("refs/heads/")) {
    BRANCH = REF.replaceAll("refs/heads/","")
}

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
        common.runPipeline(pipelineSpec, "merge", BRANCH, COMMIT_ID, "")
    }
}