pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    environment {
        // Docker Hub username
        DOCKERHUB_USER = 'YOUR_DOCKERHUB_USERNAME'

        // Frontend API URL embedded during Next.js build
        NEXT_PUBLIC_API_URL = 'http://localhost:8081'

        DOCKER_BUILDKIT = '1'
    }

    stages {

        stage('Checkout') {
            steps {
                // Uses repository configured in Jenkins UI
                checkout scm

                script {
                    env.SHORT_SHA = sh(
                        script: 'git rev-parse --short=7 HEAD',
                        returnStdout: true
                    ).trim()

                    env.API_REPO =
                        "${env.DOCKERHUB_USER}/taskflow-backend"

                    env.WEB_REPO =
                        "${env.DOCKERHUB_USER}/taskflow-frontend"
                }

                echo "Building commit: ${env.SHORT_SHA}"
            }
        }

        stage('Build Backend Image') {
            steps {
                sh '''
                    set -eu

                    docker build \
                        -f backend/Dockerfile.backend \
                        -t "$API_REPO:$SHORT_SHA" \
                        -t "$API_REPO:latest" \
                        .
                '''
            }
        }

        stage('Build Frontend Image') {
            steps {
                sh '''
                    set -eu

                    docker build \
                        -f frontend/Dockerfile.frontend \
                        --build-arg NEXT_PUBLIC_API_URL="$NEXT_PUBLIC_API_URL" \
                        -t "$WEB_REPO:$SHORT_SHA" \
                        -t "$WEB_REPO:latest" \
                        frontend
                '''
            }
        }

        stage('Push Images to Docker Hub') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'taskflow-dockerhub-credentials',
                        usernameVariable: 'DH_USER',
                        passwordVariable: 'DH_TOKEN'
                    )
                ]) {
                    sh '''
                        set -eu

                        export DOCKER_CONFIG="$WORKSPACE@tmp/docker-config"

                        mkdir -p "$DOCKER_CONFIG"

                        trap 'docker logout >/dev/null 2>&1 || true; rm -rf "$DOCKER_CONFIG"' EXIT

                        echo "$DH_TOKEN" | docker login \
                            -u "$DH_USER" \
                            --password-stdin

                        docker push "$API_REPO:$SHORT_SHA"
                        docker push "$API_REPO:latest"

                        docker push "$WEB_REPO:$SHORT_SHA"
                        docker push "$WEB_REPO:latest"
                    '''
                }
            }
        }
    }

    post {
        success {
            echo """
            ========================================
                 TASKFLOW PRO CI SUCCESS
            ========================================
            Backend:  ${env.API_REPO}:${env.SHORT_SHA}
            Frontend: ${env.WEB_REPO}:${env.SHORT_SHA}

            Both images pushed successfully.
            ========================================
            """
        }

        failure {
            echo 'TaskFlow Pro CI pipeline failed. Check stage logs.'
        }

        always {
            sh '''
                docker image prune -f >/dev/null 2>&1 || true
            '''
        }
    }
}
