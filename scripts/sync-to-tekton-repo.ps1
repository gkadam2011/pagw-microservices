# ============================================================================
# Sync script: pagw-microservices → ncr_aedleks_pagw_app
# ============================================================================
# Purpose: Sync microservice folders from development repo to Tekton deployment repo
# Usage: .\sync-to-tekton-repo.ps1 [-ServiceName <service>]
#   - No args: Syncs all services
#   - With service name: Syncs only that service
#   Example: .\sync-to-tekton-repo.ps1 -ServiceName pasorchestrator
# ============================================================================

param(
    [string]$ServiceName = ""
)

# Source and destination repo paths (adjust these for your environment)
$SourceRepo = "C:\Users\gkada\pagw-microservices"
$DestRepo = "C:\Users\gkada\ncr_aedleks_pagw_app"

# Services to sync (microservice directories)
$Services = @(
    "outboxpublisher",
    "pasapiconnector",
    "pasattachmenthandler",
    "pasbusinessvalidator",
    "pascallbackhandler",
    "pasmockpayer",
    "pasorchestrator",
    "pasproviderauth",
    "pasrequestconverter",
    "pasrequestenricher",
    "pasrequestparser",
    "pasresponsebuilder",
    "passubscriptionhandler"
)

# Shared folders to sync
$SharedFolders = @(
    "db",
    "docs",
    "infra",
    "META-INF",
    "scripts"
)

# Files to sync at root level
$RootFiles = @(
    "pom.xml",
    "Makefile",
    "docker-compose.infra.yml",
    "docker-compose.local.yml",
    "docker-compose.services.yml",
    "Dockerfile.local",
    "README.md"
)

# Exclusions
$Exclusions = @(
    ".git",
    "target",
    "node_modules",
    ".DS_Store",
    "*.log"
)

# ============================================================================
# Validation
# ============================================================================

if (-not (Test-Path $SourceRepo)) {
    Write-Host "Error: Source repo not found at $SourceRepo" -ForegroundColor Red
    Write-Host "Please update `$SourceRepo path in the script"
    exit 1
}

if (-not (Test-Path $DestRepo)) {
    Write-Host "Error: Destination repo not found at $DestRepo" -ForegroundColor Red
    Write-Host "Please update `$DestRepo path in the script"
    exit 1
}

if (-not (Test-Path "$SourceRepo\.git")) {
    Write-Host "Error: $SourceRepo is not a git repository" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path "$DestRepo\.git")) {
    Write-Host "Error: $DestRepo is not a git repository" -ForegroundColor Red
    exit 1
}

# ============================================================================
# Functions
# ============================================================================

function Sync-Directory {
    param(
        [string]$Source,
        [string]$Destination,
        [string]$Name
    )
    
    Write-Host "Syncing $Name..." -ForegroundColor Yellow
    
    # Create destination if it doesn't exist
    if (-not (Test-Path $Destination)) {
        New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    }
    
    # Use robocopy for efficient syncing (comes with Windows)
    $excludeDirs = $Exclusions | Where-Object { -not $_.Contains("*") }
    $excludeFiles = $Exclusions | Where-Object { $_.Contains("*") }
    
    $robocopyArgs = @($Source, $Destination, "/MIR", "/NFL", "/NDL", "/NJH", "/NJS", "/nc", "/ns", "/np")
    
    # Add exclusions
    foreach ($exclude in $excludeDirs) {
        $robocopyArgs += "/XD"
        $robocopyArgs += $exclude
    }
    foreach ($exclude in $excludeFiles) {
        $robocopyArgs += "/XF"
        $robocopyArgs += $exclude
    }
    
    $result = & robocopy @robocopyArgs 2>&1
    
    # Robocopy exit codes: 0-7 are success, 8+ are errors
    if ($LASTEXITCODE -gt 7) {
        Write-Host "✗ Error syncing $Name" -ForegroundColor Red
        Write-Host $result
    } else {
        Write-Host "✓ $Name synced" -ForegroundColor Green
    }
}

function Sync-File {
    param(
        [string]$Source,
        [string]$Destination,
        [string]$Name
    )
    
    Write-Host "Syncing file $Name..." -ForegroundColor Yellow
    Copy-Item -Path $Source -Destination $Destination -Force
    Write-Host "✓ $Name synced" -ForegroundColor Green
}

# ============================================================================
# Main Sync Logic
# ============================================================================

Write-Host "============================================================================"
Write-Host "  Syncing: $SourceRepo"
Write-Host "       to: $DestRepo"
Write-Host "============================================================================"
Write-Host ""

# Check if specific service requested
if ($ServiceName) {
    if ($Services -contains $ServiceName) {
        Write-Host "Syncing single service: $ServiceName" -ForegroundColor Green
        $sourcePath = Join-Path $SourceRepo $ServiceName
        $destPath = Join-Path $DestRepo $ServiceName
        
        if (Test-Path $sourcePath) {
            Sync-Directory -Source $sourcePath -Destination $destPath -Name $ServiceName
        } else {
            Write-Host "Error: Service $ServiceName not found in source repo" -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "Error: Unknown service '$ServiceName'" -ForegroundColor Red
        Write-Host "Available services:"
        $Services | ForEach-Object { Write-Host "  - $_" }
        exit 1
    }
} else {
    # Sync all services
    Write-Host "Syncing all microservices..." -ForegroundColor Green
    Write-Host ""
    
    foreach ($service in $Services) {
        $sourcePath = Join-Path $SourceRepo $service
        $destPath = Join-Path $DestRepo $service
        
        if (Test-Path $sourcePath) {
            Sync-Directory -Source $sourcePath -Destination $destPath -Name $service
        } else {
            Write-Host "⚠ Skipping $service (not found in source)" -ForegroundColor Yellow
        }
    }
    
    Write-Host ""
    Write-Host "Syncing shared folders..." -ForegroundColor Green
    Write-Host ""
    
    foreach ($folder in $SharedFolders) {
        $sourcePath = Join-Path $SourceRepo $folder
        $destPath = Join-Path $DestRepo $folder
        
        if (Test-Path $sourcePath) {
            Sync-Directory -Source $sourcePath -Destination $destPath -Name $folder
        } else {
            Write-Host "⚠ Skipping $folder (not found in source)" -ForegroundColor Yellow
        }
    }
    
    Write-Host ""
    Write-Host "Syncing root files..." -ForegroundColor Green
    Write-Host ""
    
    foreach ($file in $RootFiles) {
        $sourcePath = Join-Path $SourceRepo $file
        $destPath = Join-Path $DestRepo $file
        
        if (Test-Path $sourcePath) {
            Sync-File -Source $sourcePath -Destination $destPath -Name $file
        } else {
            Write-Host "⚠ Skipping $file (not found in source)" -ForegroundColor Yellow
        }
    }
}

Write-Host ""
Write-Host "============================================================================"
Write-Host "✓ Sync completed successfully!" -ForegroundColor Green
Write-Host "============================================================================"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. cd $DestRepo"
Write-Host "  2. git status     # Review changes"
Write-Host "  3. git add .      # Stage changes"
Write-Host "  4. git commit -m 'sync: update from pagw-microservices'"
Write-Host "  5. git push       # Push to Tekton repo"
Write-Host ""
