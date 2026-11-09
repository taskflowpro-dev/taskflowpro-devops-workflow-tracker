$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot

$script:useSudoDocker = $false
function Invoke-ProjectDocker {
    if ($script:useSudoDocker) {
        & sudo docker @args
    } else {
        & docker @args
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker was not found. Install Docker Engine with the Compose plugin on Ubuntu/Fedora, or Docker Desktop on Windows, then retry.'
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    if ($IsLinux) {
        if (-not (Get-Command systemctl -ErrorAction SilentlyContinue)) {
            throw 'Docker is unavailable. Start your Docker engine and check that docker info succeeds, then retry.'
        }

        & systemctl is-active --quiet docker
        if ($LASTEXITCODE -ne 0) {
            if (-not (Get-Command sudo -ErrorAction SilentlyContinue)) {
                throw 'Docker is not running. Start the docker service as an administrator, then retry.'
            }
            Write-Host 'Starting Docker Engine (administrator access may be requested)...'
            & sudo systemctl start docker
            if ($LASTEXITCODE -ne 0) {
                throw 'Could not start Docker Engine. Check sudo systemctl status docker and install Docker Engine if needed.'
            }
        }

        docker info *> $null
        if ($LASTEXITCODE -ne 0) {
            if (-not (Get-Command sudo -ErrorAction SilentlyContinue)) {
                throw 'Docker is unavailable to this user. Configure Docker access and check that docker info succeeds, then retry.'
            }
            Write-Host 'Checking Docker with sudo (administrator access may be requested)...'
            & sudo docker info > $null
            if ($LASTEXITCODE -ne 0) {
                throw 'Docker Engine is still unavailable. Check sudo docker info and sudo systemctl status docker, then retry.'
            }
            $script:useSudoDocker = $true
        }
    } elseif ($env:OS -eq 'Windows_NT') {
        $dockerDesktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
        if (-not (Test-Path -LiteralPath $dockerDesktop)) {
            throw 'Docker Desktop is not running and its executable was not found. Install or start Docker Desktop, then retry.'
        }

        Write-Host 'Starting Docker Desktop and waiting for its container engine...'
        Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
        $dockerReady = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            Start-Sleep -Seconds 3
            docker info *> $null
            if ($LASTEXITCODE -eq 0) {
                $dockerReady = $true
                break
            }
        }
        if (-not $dockerReady) {
            throw 'Docker Desktop did not become ready within 3 minutes. Open Docker Desktop, wait until it says it is running, then retry.'
        }
    } else {
        throw 'Docker is unavailable. Start your Docker engine and check that docker info succeeds, then retry.'
    }
}

Invoke-ProjectDocker compose version *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'The Docker Compose plugin was not found. Install the Docker Compose v2 plugin, then retry.'
}

$envPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    $examplePath = Join-Path $projectRoot '.env.example'
    if (-not (Test-Path -LiteralPath $examplePath)) {
        throw 'Neither .env nor .env.example exists in the project root.'
    }

    $randomBytes = New-Object byte[] 48
    $randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomGenerator.GetBytes($randomBytes)
    } finally {
        $randomGenerator.Dispose()
    }
    $generatedSecret = [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $generatedPassword = $generatedSecret.Substring(0, 32)

    $envText = Get-Content -LiteralPath $examplePath -Raw
    $envText = $envText.Replace('replace-with-your-postgres-password', $generatedPassword)
    $envText = $envText.Replace('replace-with-a-random-secret-of-at-least-32-bytes', $generatedSecret)
    [IO.File]::WriteAllText($envPath, $envText, [Text.UTF8Encoding]::new($false))
    Write-Host 'Created .env with generated local credentials.'
}

Write-Host 'Building and starting PostgreSQL, the API, and the frontend...'
Write-Host 'Press Ctrl+C in this terminal to stop the containers.'
Write-Host '  Frontend: http://localhost:3000'
Write-Host '  API:      http://localhost:8081'
Write-Host ''

Invoke-ProjectDocker compose up --build
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose could not start the project. See the error above, then run this script again.'
}
