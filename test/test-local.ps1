#!/usr/bin/env pwsh
<#
.SYNOPSIS
    PAGW Microservices Local Testing Script

.DESCRIPTION
    This script helps test PAGW microservices locally by:
    1. Building and starting services
    2. Submitting test FHIR bundles
    3. Checking request status

.EXAMPLE
    .\test-local.ps1 -Action build
    .\test-local.ps1 -Action start
    .\test-local.ps1 -Action test -TestFile "pas-request-professional-knee-replacement.json"
    .\test-local.ps1 -Action status -PagwId "PAGW-20251220-ABC123"
#>

param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("build", "start", "stop", "test", "status", "health", "all")]
    [string]$Action,
    
    [string]$TestFile = "pas-request-professional-knee-replacement.json",
    [string]$PagwId,
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

# Colors for output
function Write-Success { Write-Host $args -ForegroundColor Green }
function Write-Info { Write-Host $args -ForegroundColor Cyan }
function Write-Warn { Write-Host $args -ForegroundColor Yellow }
function Write-Err { Write-Host $args -ForegroundColor Red }

# Service ports
$Services = @{
    "pasorchestrator" = 8080
    "pasrequestparser" = 8081
    "pasbusinessvalidator" = 8082
    "pasrequestenricher" = 8083
    "pasattachmenthandler" = 8084
    "pasrequestconverter" = 8085
    "pasapiconnector" = 8086
    "pasresponsebuilder" = 8087
    "pascallbackhandler" = 8088
    "outboxpublisher" = 8089
    "pasproviderauth" = 8090
}

function Build-Services {
    Write-Info "Building all services..."
    
    Push-Location $ProjectRoot
    try {
        # Build parent POM first
        mvn clean install -DskipTests -q
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
        Write-Success "✓ All services built successfully"
    }
    finally {
        Pop-Location
    }
}

function Start-LocalDatabase {
    Write-Info "Starting PostgreSQL database..."
    
    # Check if Docker is running
    docker info 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Docker is not running. Please start Docker Desktop."
        return
    }
    
    # Start PostgreSQL
    docker run -d `
        --name pagw-postgres `
        -e POSTGRES_USER=pagw `
        -e POSTGRES_PASSWORD=pagw123 `
        -e POSTGRES_DB=pagw `
        -p 5432:5432 `
        postgres:15-alpine 2>&1 | Out-Null
    
    if ($LASTEXITCODE -eq 0) {
        Write-Success "✓ PostgreSQL started on port 5432"
    } else {
        Write-Warn "PostgreSQL may already be running"
    }
    
    # Wait for DB to be ready
    Start-Sleep -Seconds 3
    
    # Run migrations
    $MigrationDir = Join-Path $ProjectRoot "db\migrations"
    if (Test-Path $MigrationDir) {
        Write-Info "Running database migrations..."
        Get-ChildItem $MigrationDir -Filter "*.sql" | Sort-Object Name | ForEach-Object {
            Write-Info "  Applying: $($_.Name)"
            Get-Content $_.FullName | docker exec -i pagw-postgres psql -U pagw -d pagw
        }
    }
}

function Start-LocalSQS {
    Write-Info "Starting LocalStack for SQS..."
    
    docker run -d `
        --name pagw-localstack `
        -e SERVICES=sqs,s3,secretsmanager `
        -e DEFAULT_REGION=us-east-1 `
        -p 4566:4566 `
        localstack/localstack:latest 2>&1 | Out-Null
    
    if ($LASTEXITCODE -eq 0) {
        Write-Success "✓ LocalStack started on port 4566"
    } else {
        Write-Warn "LocalStack may already be running"
    }
    
    Start-Sleep -Seconds 5
    
    # Create queues
    Write-Info "Creating SQS queues..."
    $Queues = @(
        "pagw-orchestrator-queue",
        "pagw-request-parser-queue",
        "pagw-business-validator-queue",
        "pagw-attachment-handler-queue",
        "pagw-request-enricher-queue",
        "pagw-canonical-mapper-queue",
        "pagw-api-connector-queue",
        "pagw-response-builder-queue",
        "pagw-callback-handler-queue",
        "pagw-dlq"
    )
    
    foreach ($queue in $Queues) {
        aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name $queue 2>&1 | Out-Null
        Write-Info "  Created: $queue"
    }
}

function Start-Services {
    Write-Info "Starting services..."
    
    # Set environment variables
    $env:SPRING_PROFILES_ACTIVE = "local"
    $env:AWS_ENDPOINT = "http://localhost:4566"
    $env:AWS_REGION = "us-east-1"
    $env:AWS_ACCESS_KEY_ID = "test"
    $env:AWS_SECRET_ACCESS_KEY = "test"
    $env:PAGW_DATABASE_HOST = "localhost"
    $env:PAGW_DATABASE_PORT = "5432"
    $env:PAGW_DATABASE_NAME = "pagw"
    $env:PAGW_DATABASE_USERNAME = "pagw"
    $env:PAGW_DATABASE_PASSWORD = "pagw123"
    
    # Start orchestrator (main entry point)
    $OrchestratorDir = Join-Path $ProjectRoot "pasorchestrator\source"
    if (Test-Path $OrchestratorDir) {
        Write-Info "Starting Orchestrator on port 8080..."
        Start-Process -FilePath "mvn" -ArgumentList "spring-boot:run" -WorkingDirectory $OrchestratorDir -WindowStyle Minimized
    }
    
    Write-Success "✓ Services starting... Check individual logs for status"
    Write-Info "Orchestrator API: http://localhost:8080/api/v1/pas/Claim/`$submit"
}

function Stop-Services {
    Write-Info "Stopping services..."
    
    # Stop Docker containers
    docker stop pagw-postgres pagw-localstack 2>&1 | Out-Null
    docker rm pagw-postgres pagw-localstack 2>&1 | Out-Null
    
    # Kill Java processes (be careful!)
    # Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
    
    Write-Success "✓ Services stopped"
}

function Test-HealthChecks {
    Write-Info "Checking service health..."
    
    foreach ($service in $Services.GetEnumerator()) {
        $url = "http://localhost:$($service.Value)/actuator/health"
        try {
            $response = Invoke-RestMethod -Uri $url -TimeoutSec 5 -ErrorAction SilentlyContinue
            if ($response.status -eq "UP") {
                Write-Success "✓ $($service.Key): UP"
            } else {
                Write-Warn "⚠ $($service.Key): $($response.status)"
            }
        }
        catch {
            Write-Err "✗ $($service.Key): Not responding"
        }
    }
}

function Submit-TestRequest {
    param([string]$TestFile)
    
    $FixturesDir = Join-Path $ProjectRoot "test\fixtures"
    $FilePath = Join-Path $FixturesDir $TestFile
    
    if (-not (Test-Path $FilePath)) {
        Write-Err "Test file not found: $FilePath"
        return
    }
    
    Write-Info "Submitting test request: $TestFile"
    
    $Bundle = Get-Content $FilePath -Raw
    $IdempotencyKey = [guid]::NewGuid().ToString()
    
    $Headers = @{
        "Content-Type" = "application/fhir+json"
        "Accept" = "application/fhir+json"
        "X-Idempotency-Key" = $IdempotencyKey
        "X-Tenant-ID" = "TEST-TENANT"
        "X-Correlation-ID" = [guid]::NewGuid().ToString()
    }
    
    try {
        $response = Invoke-RestMethod `
            -Uri "$BaseUrl/api/v1/pas/Claim/`$submit" `
            -Method Post `
            -Body $Bundle `
            -Headers $Headers `
            -ContentType "application/fhir+json"
        
        Write-Success "✓ Request submitted successfully"
        Write-Info "PAGW ID: $($response.pagwId)"
        Write-Info "Status: $($response.status)"
        Write-Info "Idempotency Key: $IdempotencyKey"
        
        # Pretty print response
        $response | ConvertTo-Json -Depth 10 | Write-Host
        
        return $response.pagwId
    }
    catch {
        Write-Err "✗ Request failed: $_"
        if ($_.Exception.Response) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            Write-Err $reader.ReadToEnd()
        }
    }
}

function Get-RequestStatus {
    param([string]$PagwId)
    
    Write-Info "Checking status for: $PagwId"
    
    try {
        $response = Invoke-RestMethod `
            -Uri "$BaseUrl/api/v1/pas/status/$PagwId" `
            -Method Get `
            -Headers @{ "Accept" = "application/fhir+json" }
        
        Write-Success "Status: $($response.status)"
        $response | ConvertTo-Json -Depth 10 | Write-Host
    }
    catch {
        Write-Err "✗ Status check failed: $_"
    }
}

# Main execution
switch ($Action) {
    "build" {
        Build-Services
    }
    "start" {
        Start-LocalDatabase
        Start-LocalSQS
        Start-Services
    }
    "stop" {
        Stop-Services
    }
    "test" {
        Submit-TestRequest -TestFile $TestFile
    }
    "status" {
        if (-not $PagwId) {
            Write-Err "Please provide -PagwId parameter"
            exit 1
        }
        Get-RequestStatus -PagwId $PagwId
    }
    "health" {
        Test-HealthChecks
    }
    "all" {
        Build-Services
        Start-LocalDatabase
        Start-LocalSQS
        Start-Services
        Start-Sleep -Seconds 30  # Wait for services to start
        Test-HealthChecks
        Submit-TestRequest -TestFile $TestFile
    }
}
