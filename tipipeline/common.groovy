import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import groovy.json.JsonBuilder

class Credential {
    String jenkinsID;
    String key;    
}

class Var {
    String value;
    String key;      
}

class Resource {
    String memory;
    String cpu;
}

class Resources {
    Resource limits;
    Resource requests;
}


class TaskSpec {
    String pipelineID;
    String taskID;
    String taskName;
    String checkerName;
    String pipelineName;
    String triggerEvent;
    String branch;
    String pullRequest;
    String commitID;
    String owner;
    String repo;
    String cacheCodeURL;
    Integer retry;
    Integer timeout;
    Credential[] credentials;
    Var[] vars;
    String image;
    Resources resources;
    Map params;
}

class Triggers {
}

class Notify {
}


class PipelineSpec {
    String pipelineName;
    String pipelineID;
    String repo;
    String owner;
    String defaultRef;
    Triggers triggers;
    Notify  notify;
    TaskSpec[] tasks;
}

def loadPipelineConfig(fileURL) {
    ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory())
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false)
    yamlRequest = httpRequest url: fileURL, httpMode: 'GET'
    PipelineSpec pipelineSpec = objectMapper.readValue(yamlRequest.content, PipelineSpec.class)
    repoInfo = pipelineSpec.repo.split("/")
    if (repoInfo.length==2){
        pipelineSpec.owner = repoInfo[0]
        pipelineSpec.repo = repoInfo[1]
    }
    return pipelineSpec
}


def createPipelineRun(PipelineSpec pipeline) {
    // create pipelinerun to tipipeline and get pipeline_id, task_id
}


def cacheCode(repo,commitID,branch,prID) {
    cacheCodeParams = [
        string(name: 'ORG_AND_REPO', value: repo),
        string(name: 'COMMIT_ID', value: commitID),
    ]
    if (branch != "" && branch != null ) {
        cacheCodeParams.push(string(name: 'BRANCH', value: branch))
    }
    if (prID != "" && prID != null ) {
        cacheCodeParams.push(string(name: 'PULL_ID', value: prID))
    }
    triggerTask("cache-code",cacheCodeParams)
}


def runPipeline(PipelineSpec pipeline, String triggerEvent, String branch, String commitID, String pullRequest) {
    pipelineinfo = createPipelineRun(pipeline)
    cacheCode("${pipeline.owner}/${pipeline.repo}",commitID,branch,pullRequest)
    jobs = [:]
    for (task in pipeline.tasks) {
        jobs[task.taskName] = {
            def cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${pipeline.repo}/${commitID}/${pipeline.repo}.tar.gz"
            task.pipelineName = pipeline.pipelineName
            task.triggerEvent = triggerEvent
            task.branch = branch 
            task.commitID = commitID
            task.pullRequest = pullRequest
            task.cacheCodeURL = cacheCodeUrl
            task.repo = pipeline.repo
            task.owner = pipeline.owner
            taskJsonString = new JsonBuilder(task).toPrettyString()

            params = [
            string(name: "INPUT_JSON", value: taskJsonString),
            ]
            result = build(job: task.checkerName, parameters: params, wait: true, propagate: false)
        }
    }
    parallel jobs
}



def loadTaskConfig(String config) {
    ObjectMapper objectMapper = new ObjectMapper()
    TaskSpec taskSpec = objectMapper.readValue(config, TaskSpec.class)
    if (taskSpec.timeout == null || taskSpec.timeout <= 1) {
        taskSpec.timeout = 60
    }
    if (taskSpec.retry == null || taskSpec.retry > 10) {
        taskSpec.retry = 0
    }
    taskSpec.resources = defaultResourceValue(taskSpec.resources)
    return taskSpec
}

def defaultResourceValue(Resources resource) {
    resp = new Resources()
    limit = new Resource()
    request = new Resource()
    resp.limits = limit
    resp.requests = request
    if (resource == null) {
        resp.requests.cpu = "1000m"
        resp.requests.memory = "2Gi"
        resp.limits.cpu = "1000m"
        resp.limits.memory = "2Gi"
        return resp
    }
    if (resource.requests == null || resource.requests.cpu.length < 1 || resource.requests.memory.length < 1) {
        resp.requests.cpu = "1000m"
        resp.requests.memory = "2Gi"
        resp.limits.cpu = "1000m"
        resp.limits.memory = "2Gi"
        return resp
    }
    if (resource.limits == null || resource.limits.cpu.length < 1 || resource.limits.memory.length < 1) {
        resp.limits.cpu = resource.requests.cpu
        resp.limits.memory = resource.requests.memory
        return resp
    }
    resp = resource
    return resp
}


def updateTaskStatus(String status,TaskSpec config) {
    // update taskrun to tipipeline by task_id
}

def runWithPod(TaskSpec config, Closure body) {
    def label = config.pipelineName + "-" + config.taskName + "-" + UUID.randomUUID().toString()
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'node', alwaysPullImage: false,
                            image: config.image, ttyEnabled: true,
                            resourceRequestCpu: config.resources.requests.cpu, resourceRequestMemory: config.resources.requests.memory,
                            resourceLimitCpu: config.resources.limits.cpu, resourceLimitMemory: config.resources.limits.memory,
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                    nfsVolume(mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false),
            ],
    ) {
        // timeout(time: config.timeout, unit: 'MINUTES') {
        //     retry(config.retry){
        //         node(label) {
        //             container("node") {
        //                 updateTaskStatus("running",config)
        //                 println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
        //                 status = body(config)
        //                 updateTaskStatus(status,config)
        //             }
        //         }
        //     }
        // }
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
    
}


return this




// String json = '{"retry" : 1,"timeout" : 10,"vars":[{"key":"123qwe"}],"params":{"a":"b"}}'

// taskSpec = loadConfig(json)
// println taskSpec.params["a"]