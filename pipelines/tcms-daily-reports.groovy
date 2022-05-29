properties([
    pipelineTriggers([cron('H 0 * * *')]),
])

tcmsHost = "https://tcms.pingcap.net/"

triggerCI = httpRequest url: tcmsHost + "api/v1/dailyci/trigger", httpMode: 'POST'
ciResp = readJSON text: triggerCI.content
id = ciResp["id"].toString()
ciFinished = false
ciDuration = 0

stage("Wait for completion") {
    while(!ciFinished) {
        sleep(300)
        ciDuration = ciDuration +300
        // ci breaks when timeout(23 hours)
        if (ciDuration > 82800) {
            break
        }
        statusCI = httpRequest tcmsHost + "api/v1/dailyci/trigger/" + id
        statusResp = readJSON text: statusCI.content
        ciFinished = statusResp["finished"].toBoolean()
    }
}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-ti-pipeline"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest", ttyEnabled: true,
                        resourceRequestCpu: '200m', resourceRequestMemory: '1Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]     
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

def response = httpRequest tcmsHost + "api/v1/plans/branchs"
def branches = readJSON text: response.content
for (b in branches) {
    branch= b.toString()
    run_with_pod{
        container("golang"){
            stage("branch: "+ branch + " daily ci result") {
                sh """
                curl -o ${branch}.xml ${tcmsHost}api/v1/dailyci?started_at=${id}&branch=${branch}
                """
                junit testResults: "${branch}.xml"
            }
        }
    }
}
