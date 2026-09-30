# 本机 Docker Desktop -> 第4层 K8s 一键脚本
# 用法（在项目根目录执行）:
#   powershell -File k8s/ops.ps1 -Action up
#   powershell -File k8s/ops.ps1 -Action redeploy
#   powershell -File k8s/ops.ps1 -Action forward
#   powershell -File k8s/ops.ps1 -Action status
#   powershell -File k8s/ops.ps1 -Action down

param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('up', 'init', 'redeploy', 'redeploy-parser', 'redeploy-api', 'forward', 'status', 'down')]
    [string]$Action
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
Set-Location $Root

$Node = 'desktop-control-plane'
$OllamaImage = 'swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/ollama/ollama:0.30.2'
$ParserImage = 'rag-doc-parser:latest'
$ApiImage = 'rag-api:latest'

function Assert-Tools {
    foreach ($cmd in @('docker', 'kubectl')) {
        if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
            throw "Missing command: $cmd"
        }
    }
    # Docker Desktop 的 K8s 节点有时不出现在 docker ps，用 inspect 判断
    docker inspect $Node 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "K8s node container not found: $Node. Enable Kubernetes in Docker Desktop first."
    }
    kubectl get nodes 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'kubectl cannot reach cluster. Check Docker Desktop Kubernetes is running.'
    }
}

function Get-OllamaVolume {
    $vols = @(docker volume ls --format '{{.Name}}')
    $candidates = @(
        'java-ai-rag-tender-system_ollama_data',
        'ollama_data'
    )
    foreach ($name in $candidates) {
        if ($vols -contains $name) {
            return $name
        }
    }
    throw 'Ollama volume not found. Run: docker compose up -d ollama'
}

function Get-KindVarVolume {
    $mounts = docker inspect $Node --format '{{range .Mounts}}{{.Name}}={{.Destination}}{{println}}{{end}}'
    foreach ($line in ($mounts -split "`n")) {
        $trim = $line.Trim()
        if ($trim -match '^(.+)=/var$') {
            return $Matches[1]
        }
    }
    throw "Cannot resolve /var volume on node: $Node"
}

function Import-Image([string]$image) {
    Write-Host ">> import image: $image" -ForegroundColor Cyan
    $cmd = "docker save `"$image`" | docker exec -i $Node ctr -n k8s.io images import -"
    cmd /c $cmd
    if ($LASTEXITCODE -ne 0) {
        throw "import failed: $image"
    }
}

function Build-All-Apps {
    Write-Host '>> build rag-doc-parser ...' -ForegroundColor Green
    docker build -t $ParserImage ./document-parser
    if ($LASTEXITCODE -ne 0) { throw 'build parser failed' }

    Write-Host '>> build rag-api ...' -ForegroundColor Green
    docker build -t $ApiImage .
    if ($LASTEXITCODE -ne 0) { throw 'build api failed' }
}

function Stop-Compose-Apps {
    Write-Host '>> stop compose apps (ollama/doc-parser/rag-api)...' -ForegroundColor Yellow
    docker stop ollama doc-parser rag-api 2>$null | Out-Null
}

function Ensure-Infra {
    Write-Host '>> start infra (etcd/minio/milvus/redis)...' -ForegroundColor Green
    docker compose up -d etcd minio standalone redis
    if ($LASTEXITCODE -ne 0) { throw 'infra start failed' }
}

function Copy-Models {
    $ollamaVol = Get-OllamaVolume
    $kindVar = Get-KindVarVolume
    Write-Host ">> copy models: $ollamaVol -> node /var/ollama-models" -ForegroundColor Green
    docker run --rm --entrypoint sh `
        -v "${ollamaVol}:/from:ro" `
        -v "${kindVar}:/to" `
        $OllamaImage `
        -c 'mkdir -p /to/ollama-models; cp -a /from/. /to/ollama-models/; du -sh /to/ollama-models'
    if ($LASTEXITCODE -ne 0) { throw 'copy models failed' }
}

function Apply-All {
    Write-Host '>> kubectl apply ...' -ForegroundColor Green
    kubectl apply -f "$Root\k8s\00-namespace.yaml"
    kubectl apply -f "$Root\k8s\01-ollama.yaml"
    kubectl apply -f "$Root\k8s\02-doc-parser.yaml"
    kubectl apply -f "$Root\k8s\03-rag-java.yaml"
}

function Wait-Ready {
    kubectl -n rag-tender rollout status deploy/ollama --timeout=180s
    kubectl -n rag-tender rollout status deploy/doc-parser --timeout=180s
    kubectl -n rag-tender rollout status deploy/rag-java --timeout=240s
}

function Start-Forward {
    Write-Host '>> port-forward windows opened' -ForegroundColor Green
    Write-Host '   web:    http://localhost:30081' -ForegroundColor Green
    Write-Host '   parser: http://localhost:30099/health' -ForegroundColor Green
    Write-Host '   ollama: http://localhost:31134/api/tags' -ForegroundColor Green
    Start-Process powershell -ArgumentList '-NoExit', '-Command', 'kubectl -n rag-tender port-forward svc/rag-java 30081:8081'
    Start-Process powershell -ArgumentList '-NoExit', '-Command', 'kubectl -n rag-tender port-forward svc/doc-parser 30099:9099'
    Start-Process powershell -ArgumentList '-NoExit', '-Command', 'kubectl -n rag-tender port-forward svc/ollama 31134:11434'
}

function Deploy-Full {
    Assert-Tools
    Ensure-Infra
    Stop-Compose-Apps
    Build-All-Apps
    Import-Image $OllamaImage
    Import-Image $ParserImage
    Import-Image $ApiImage
    Copy-Models
    Apply-All
    kubectl -n rag-tender rollout restart deploy/ollama deploy/doc-parser deploy/rag-java
    Wait-Ready
    Start-Forward
    kubectl -n rag-tender get pods,svc
    Write-Host ''
    Write-Host 'DONE. Open http://localhost:30081' -ForegroundColor Green
}

function Redeploy-Apps {
    Assert-Tools
    Stop-Compose-Apps
    Build-All-Apps
    Import-Image $ParserImage
    Import-Image $ApiImage
    kubectl apply -f "$Root\k8s\02-doc-parser.yaml"
    kubectl apply -f "$Root\k8s\03-rag-java.yaml"
    kubectl -n rag-tender rollout restart deploy/doc-parser deploy/rag-java
    kubectl -n rag-tender rollout status deploy/doc-parser --timeout=180s
    kubectl -n rag-tender rollout status deploy/rag-java --timeout=240s
    kubectl -n rag-tender get pods
    Write-Host 'DONE. If page down: powershell -File k8s/ops.ps1 -Action forward' -ForegroundColor Green
}

switch ($Action) {
    { $_ -in @('up', 'init') } { Deploy-Full }
    'redeploy' { Redeploy-Apps }
    'redeploy-parser' {
        Assert-Tools
        docker build -t $ParserImage ./document-parser
        if ($LASTEXITCODE -ne 0) { throw 'build parser failed' }
        Import-Image $ParserImage
        kubectl apply -f "$Root\k8s\02-doc-parser.yaml"
        kubectl -n rag-tender rollout restart deploy/doc-parser
        kubectl -n rag-tender rollout status deploy/doc-parser --timeout=180s
    }
    'redeploy-api' {
        Assert-Tools
        docker build -t $ApiImage .
        if ($LASTEXITCODE -ne 0) { throw 'build api failed' }
        Import-Image $ApiImage
        kubectl apply -f "$Root\k8s\03-rag-java.yaml"
        kubectl -n rag-tender rollout restart deploy/rag-java
        kubectl -n rag-tender rollout status deploy/rag-java --timeout=240s
    }
    'forward' { Assert-Tools; Start-Forward }
    'status' { Assert-Tools; kubectl -n rag-tender get pods,svc }
    'down' {
        Assert-Tools
        kubectl delete namespace rag-tender --ignore-not-found
        kubectl delete pv ollama-models-pv --ignore-not-found
        Write-Host 'K8s apps removed. Back to compose: docker compose up -d' -ForegroundColor Yellow
    }
}
