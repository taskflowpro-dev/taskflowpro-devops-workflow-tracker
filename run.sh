#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

die() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

run_admin() {
    if (( EUID == 0 )); then
        "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo "$@"
    else
        die 'Administrator access is needed. Install sudo or start Docker and configure user access, then retry.'
    fi
}

platform=$(uname -s)
case "$platform" in
    MINGW*|MSYS*|CYGWIN*) platform=windows ;;
    Linux*) platform=linux ;;
    *) die 'Run this script on Ubuntu/Fedora with Bash, or on Windows with Git Bash.' ;;
esac

find_docker_desktop() {
    local install_root candidate
    for install_root in "${ProgramW6432:-}" "${PROGRAMFILES:-}" "${ProgramFiles:-}" '/c/Program Files'; do
        [[ -n "$install_root" ]] || continue
        if command -v cygpath >/dev/null 2>&1; then
            install_root=$(cygpath -u "$install_root")
        fi
        candidate="$install_root/Docker/Docker/Docker Desktop.exe"
        if [[ -f "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    if [[ -n "${LOCALAPPDATA:-}" ]]; then
        install_root=$LOCALAPPDATA
        if command -v cygpath >/dev/null 2>&1; then
            install_root=$(cygpath -u "$install_root")
        fi
        candidate="$install_root/Docker/Docker Desktop.exe"
        if [[ -f "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi
    return 1
}

if [[ "$platform" == windows ]] && ! command -v docker >/dev/null 2>&1; then
    if docker_desktop=$(find_docker_desktop); then
        export PATH="$(dirname -- "$docker_desktop")/resources/bin:$PATH"
    fi
fi

command -v docker >/dev/null 2>&1 ||
    die 'Install Docker Desktop on Windows, or Docker Engine with the Compose plugin on Ubuntu/Fedora, then run ./run.sh again.'

docker_command=(docker)
if ! docker info >/dev/null 2>&1; then
    if [[ "$platform" == windows ]]; then
        docker_desktop=$(find_docker_desktop) ||
            die 'Docker Desktop was not found. Start your Docker engine manually, then retry.'
        printf 'Starting Docker Desktop and waiting for its Linux container engine...\n'
        "$docker_desktop" >/dev/null 2>&1 &
        docker_ready=false
        for (( attempt=0; attempt<60; attempt++ )); do
            if docker info >/dev/null 2>&1; then
                docker_ready=true
                break
            fi
            sleep 3
        done
        [[ "$docker_ready" == true ]] ||
            die 'Docker Desktop did not become ready within 3 minutes. Open Docker Desktop, select Linux containers, and retry.'
    else
        command -v systemctl >/dev/null 2>&1 ||
            die 'Docker is unavailable. Start your Docker engine and check that docker info succeeds, then retry.'

        if ! systemctl is-active --quiet docker; then
            printf 'Starting Docker Engine (administrator access may be requested)...\n'
            run_admin systemctl start docker ||
                die 'Could not start Docker Engine. Check systemctl status docker, then retry.'
        fi

        if ! docker info >/dev/null 2>&1; then
            printf 'Checking Docker with administrator access...\n'
            run_admin docker info >/dev/null ||
                die 'Docker Engine is still unavailable. Check Docker permissions and service status.'
            if (( EUID != 0 )); then
                docker_command=(sudo docker)
            fi
        fi
    fi
fi

"${docker_command[@]}" compose version >/dev/null 2>&1 ||
    die 'Install the Docker Compose plugin, then run ./run.sh again.'

if [[ ! -e .env ]]; then
    [[ -f .env.example ]] || die 'Neither .env nor .env.example exists in the project root.'
    generated_secret=$(od -An -N48 -tx1 /dev/urandom | tr -d ' \n')
    [[ ${#generated_secret} -eq 96 ]] || die 'Could not generate local credentials.'
    (
        umask 077
        sed \
            -e "s/replace-with-your-postgres-password/${generated_secret:0:32}/g" \
            -e "s/replace-with-a-random-secret-of-at-least-32-bytes/$generated_secret/g" \
            .env.example > .env
    )
    unset generated_secret
    printf 'Created .env with generated local credentials.\n'
fi

printf '%s\n' \
    'Building and starting PostgreSQL, the API, and the frontend...' \
    'Press Ctrl+C in this terminal to stop the containers.' \
    '  Frontend: http://localhost:3000' \
    '  API:      http://localhost:8081'

exec "${docker_command[@]}" compose up --build
