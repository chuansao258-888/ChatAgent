param(
    [string]$SpringProfiles = "local-gpu",
    [string]$JavaHome = "C:\Users\guany\.jdks\ms-17.0.18",
    [switch]$Offline
)

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$backendDir = Join-Path $repoRoot "chatagent"

if (-not (Test-Path $backendDir)) {
    throw "Backend directory not found: $backendDir"
}

function Resolve-JavaHomePath {
    param(
        [string]$ConfiguredJavaHome,
        [string]$FallbackJavaHome
    )

    $candidate = if (-not [string]::IsNullOrWhiteSpace($ConfiguredJavaHome)) {
        $ConfiguredJavaHome.Trim()
    } else {
        $FallbackJavaHome.Trim()
    }

    if ([string]::IsNullOrWhiteSpace($candidate)) {
        return $null
    }

    $normalized = $candidate.TrimEnd('\')
    if ((Split-Path $normalized -Leaf) -ieq "bin") {
        $normalized = Split-Path $normalized -Parent
    }

    $javaExe = Join-Path $normalized "bin\java.exe"
    if (-not (Test-Path $javaExe)) {
        throw "Configured JAVA_HOME is invalid: $candidate (expected java.exe under $normalized\bin)"
    }

    return $normalized
}

$resolvedJavaHome = Resolve-JavaHomePath -ConfiguredJavaHome $env:JAVA_HOME -FallbackJavaHome $JavaHome
if ($resolvedJavaHome) {
    $env:JAVA_HOME = $resolvedJavaHome
    if (-not ($env:Path -split ';' | Where-Object { $_ -eq (Join-Path $resolvedJavaHome 'bin') })) {
        $env:Path = "$(Join-Path $resolvedJavaHome 'bin');$env:Path"
    }
}

$env:SPRING_PROFILES_ACTIVE = $SpringProfiles

function Resolve-EnvValue {
    param(
        [string]$Value,
        [string]$DefaultValue
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $DefaultValue
    }
    return $Value
}

Write-Host "Starting ChatAgent backend with local GPU profile" -ForegroundColor Cyan
Write-Host "  Profiles : $($env:SPRING_PROFILES_ACTIVE)"
Write-Host "  JAVA_HOME: $($env:JAVA_HOME)"
Write-Host "  Embedding: $(Resolve-EnvValue $env:CHATAGENT_RAG_EMBEDDING_BASE_URL 'http://127.0.0.1:11434')"
Write-Host "  Reranker : $(Resolve-EnvValue $env:CHATAGENT_RAG_RERANKER_BASE_URL 'http://127.0.0.1:7997')"
Write-Host "  MinerU   : $(Resolve-EnvValue $env:CHATAGENT_RAG_VDP_MINERU_BASE_URL 'http://127.0.0.1:8000')"

Push-Location $backendDir
try {
    Write-Host "Preparing reactor dependencies (framework, infra)..." -ForegroundColor DarkCyan
    if ($Offline) {
        & .\mvnw.cmd -o -pl framework,infra -am -DskipTests install
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to install framework/infra dependencies"
        }
        & .\mvnw.cmd -o -pl bootstrap spring-boot:run
    } else {
        & .\mvnw.cmd -pl framework,infra -am -DskipTests install
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to install framework/infra dependencies"
        }
        & .\mvnw.cmd -pl bootstrap spring-boot:run
    }
} finally {
    Pop-Location
}
