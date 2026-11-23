pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    // Jenkins checks GitHub every 2 minutes and runs this pipeline only if there is a new commit.
    // (No public webhook needed, so it works on a local machine.)
    triggers {
        pollSCM('H/2 * * * *')
    }

    environment {
        // CHANGE THESE
        GIT_URL        = 'https://github.com/taskflowpro-dev/REPO-NAME.git'
        GIT_BRANCH     = 'main'
        DOCKERHUB_USER = 'your-dockerhub-username'

        // Baked into the frontend image at build time (browser calls this URL).
        NEXT_PUBLIC_API_URL = 'http://localhost:8081'

        // Rootless Docker: use this user's socket unless Jenkins already sets DOCKER_HOST.
        DOCKER_HOST   = sh(returnStdout: true, script: 'echo "${DOCKER_HOST:-unix:///run/user/$(id -u)/docker.sock}"').trim()
        DOCKER_BUILDKIT = '1'
    }

    stages {
        stage('Checkout') {
            steps {
                // Public repo: no credentials needed.
                // Private repo: add  credentialsId: 'github-creds'  (Username + token) below.
                checkout([$class: 'GitSCM',
                    branches: [[name: "*/${env.GIT_BRANCH}"]],
                    userRemoteConfigs: [[url: env.GIT_URL]]
                ])
                script {
                    env.SHORT_SHA = sh(returnStdout: true, script: 'git rev-parse --short=7 HEAD').trim()
                    env.API_REPO  = "${env.DOCKERHUB_USER}/taskflow-backend"
                    env.WEB_REPO  = "${env.DOCKERHUB_USER}/taskflow-frontend"
                }
                echo "Building commit ${env.SHORT_SHA}"
            }
        }

        stage('Build Images') {
            steps {
                sh '''
                    set -eu
                    docker build -f backend/Dockerfile.backend \
                        -t "$API_REPO:$SHORT_SHA" -t "$API_REPO:latest" .

                    docker build -f frontend/Dockerfile.frontend \
                        --build-arg NEXT_PUBLIC_API_URL="$NEXT_PUBLIC_API_URL" \
                        -t "$WEB_REPO:$SHORT_SHA" -t "$WEB_REPO:latest" frontend
                '''
            }
        }

        stage('Push to Docker Hub') {
            steps {
                // Jenkins credential: kind "Username with password",
                // ID "dockerhub-creds", password = a Docker Hub access token.
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds',
                                                  usernameVariable: 'DH_USER',
                                                  passwordVariable: 'DH_TOKEN')]) {
                    sh '''
                        set -eu
                        export DOCKER_CONFIG="$WORKSPACE@tmp/docker-config"
                        mkdir -p "$DOCKER_CONFIG"
                        echo "$DH_TOKEN" | docker login -u "$DH_USER" --password-stdin

                        docker push "$API_REPO:$SHORT_SHA"
                        docker push "$API_REPO:latest"
                        docker push "$WEB_REPO:$SHORT_SHA"
                        docker push "$WEB_REPO:latest"

                        docker logout
                    '''
                }
            }
        }
    }

    post {
        success { echo "Pushed ${env.API_REPO} and ${env.WEB_REPO} (tags: ${env.SHORT_SHA}, latest)" }
        always  { sh 'docker image prune -f >/dev/null 2>&1 || true' }
    }
}
