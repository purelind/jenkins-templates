properties([
        parameters([
                string(
                        defaultValue: 'pingcap/tidb',
                        name: 'REPO',
                        trim: true
                )
        ]),
        pipelineTriggers([cron('H H * * *')])
])

repo = "" // chanage to origin repo
org = ""
repoInfo = REPO.split("/")
if (repoInfo.length == 2) {
    org = repoInfo[0]
    repo = repoInfo[1]
}

def get_sha(branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

def configfile = "https://raw.githubusercontent.com/PingCAP-QE/devops-config/master/${repo}/daily.yaml"


def runtasks(branch,repo,commitID,tasks,common) {
    jobs = [:]
    def task_result_array = []
    for (task in tasks) {
        taskType = task.taskType.toString()
        taskName =task.name.toString()
        switch(taskType) {
            case "build":
                def buildConfig = common.parseBuildConfig(task)
                jobs[taskName] = {
                    result_map = common.buildBinary(buildConfig,repo,commitID)
                    task_result_array << result_map
                }
                break
            case "unit-test":
                def unitTestConfig = common.parseUnitTestConfig(task)
                jobs[taskName] = {
                    result_map = common.unitTest(unitTestConfig,repo,commitID)
                    task_result_array << result_map
                }
                break
            case "lint":
                def lintConfig = common.parseLintConfig(task)
                jobs[taskName] = {
                    common.codeLint(lintConfig,repo,commitID)
                    task_result_array << result_map
                }
                break
            case "cyclo": 
                def cycloConfig = common.parseCycloConfig(task)
                jobs[taskName] = {
                    result_map = common.codeCyclo(cycloConfig,repo,commitID)
                    task_result_array << result_map
                }
                break
            case "gosec":
                def gosecConfig = common.parseGosecConfig(task)
                jobs[taskName] = {
                    common.codeGosec(gosecConfig,repo,commitID)
                    task_result_array << result_map
                }
                break
            case "common":
                def commonConfig = common.parseCommonConfig(task)
                jobs[taskName] = {
                    common.codeCommon(commonConfig,repo,commitID,branch)
                    task_result_array << result_map
                }
                break
        }
    }
    parallel jobs

    return task_result_array
}

node("${GO_BUILD_SLAVE}") {
    container("golang") {
        // TODO: debug daily-check.groovy
        // checkout scm
        // def common = load "pipelines/common.groovy"
        sh "wget https://raw.githubusercontent.com/purelind/jenkins-templates/purelind/get-task-result-info/pipelines/common.groovy"
        def common = load "common.groovy"
        
        configs = common.getConfig(configfile)
        refs  = configs.defaultRefs
        taskFailed = false
        for (ref in refs) {
            def task_result_array = []
            def commitID = get_sha(ref)
            try {
                stage("Branch: " + ref) {
                    // TODO: debug daily-check.groovy
                    // common.cacheCode(REPO,commitID,ref,"")
                    task_result_array = runtasks(ref,repo,commitID,configs.tasks,common) 
                }     
            } catch (Exception e) {
                taskFailed = true
                throw e
            }  finally {
                stage("Summary") {
                   for (result_map in task_result_array) {
                    if (result_map.taskResult != "SUCCESS") {
                        taskFailed = true
                    }
                    if (result_map.taskSummary != null && result_map.taskSummary != "") {
                        println("${result_map.name} ${result_map.taskResult}: ${result_map.taskSummary}")
                        println("${result_map.name} #${result_map.buildNumber}: ${result_map.url}")
                    }
                    
                } 
                }
                
            }         
        }
        if (taskFailed) {
            currentBuild.result = "FAILURE"
        }
    }
}
