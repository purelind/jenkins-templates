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
    task_result_array = []
    for (task in tasks) {
        taskType = task.taskType.toString()
        taskName =task.name.toString()
        switch(taskType) {
            case "build":
                def buildConfig = common.parseBuildConfig(task)
                jobs[taskName] = {
                    result = common.buildBinary(buildConfig,repo,commitID
                    task_result_array.add({"name": taskName,
                                            "result": result})
                }
                break
            case "unit-test":
                def unitTestConfig = common.parseUnitTestConfig(task)
                jobs[taskName] = {
                    common.unitTest(unitTestConfig,repo,commitID)
                }
                break
            case "lint":
                def lintConfig = common.parseLintConfig(task)
                jobs[taskName] = {
                    common.codeLint(lintConfig,repo,commitID)
                }
                break
            case "cyclo": 
                def cycloConfig = common.parseCycloConfig(task)
                jobs[taskName] = {
                    result = common.codeCyclo(cycloConfig,repo,commitID)
                    task_result_array.add({"name": taskName,
                                            "result": result})
                }
                break
            case "gosec":
                def gosecConfig = common.parseGosecConfig(task)
                jobs[taskName] = {
                    common.codeGosec(gosecConfig,repo,commitID)
                }
                break
            case "common":
                def commonConfig = common.parseCommonConfig(task)
                jobs[taskName] = {
                    common.codeCommon(commonConfig,repo,commitID,branch)
                }
                break
        }
    }
    parallel jobs

    return task_result_array
}

node("${GO_BUILD_SLAVE}") {
    container("golang") {
        checkout scm
        def common = load "pipelines/common.groovy"
        configs = common.getConfig(configfile)
        refs  = configs.defaultRefs
        taskFailed = false
        task_result_array = []
        for (ref in refs) {
            def commitID = get_sha(ref)
            try {
                stage("verify: " + ref) {
                    common.cacheCode(REPO,commitID,ref,"")
                    task_result_array = runtasks(ref,repo,commitID,configs.tasks,common) 
                }     
            } catch (Exception e) {
                taskFailed = true
            }           
        }

        for (task_result in task_result_array) {
            echo task_result.getAbsoluteUrl()
            echo task_result.getResult()
        }

        
        // if (taskFailed) {
        //     throw new RuntimeException("task failed")
        // }
    }
}
