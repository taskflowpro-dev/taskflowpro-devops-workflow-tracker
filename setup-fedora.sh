#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if [[ ! -r /etc/os-release ]]; then
    printf 'Error: Could not identify the operating system.\n' >&2
    exit 1
fi
. /etc/os-release
if [[ "${ID:-}" != fedora ]]; then
    printf 'Error: This installer is for Fedora. Use ./run.sh on an already configured system.\n' >&2
    exit 1
fi

if (( EUID == 0 )); then
    printf 'Run this script as your normal user; it uses sudo for installation.\n' >&2
    exit 1
fi
command -v sudo >/dev/null 2>&1 || {
    printf 'Error: sudo is required to install Docker.\n' >&2
    exit 1
}

printf 'Installing Docker Engine and Compose using Docker\047s official Fedora repository.\n'
sudo -v
sudo dnf install -y dnf5-plugins
if [[ ! -f /etc/yum.repos.d/docker-ce.repo ]]; then
    sudo dnf config-manager addrepo \
        --from-repofile https://download.docker.com/linux/fedora/docker-ce.repo
fi
sudo dnf install -y docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker

printf 'Verifying Docker Engine and Compose...\n'
sudo docker info >/dev/null
sudo docker compose version
sudo docker run --rm hello-world

printf 'Fedora setup complete. Starting TaskFlow Pro...\n'
exec bash ./run.sh
