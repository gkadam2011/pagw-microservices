# =====================================================
# Local Database Initialization Script (PowerShell)
# Runs migrations against local PostgreSQL
# =====================================================

param(
    [string]$DbHost = $env:PAGW_DATABASE_HOST ?? "localhost",
    [string]$DbPort = $env:PAGW_DATABASE_PORT ?? "5432",
    [string]$DbName = $env:PAGW_DATABASE_NAME ?? "pagw",
    [string]$DbUser = $env:PAGW_DATABASE_USERNAME ?? "pagw",
    [string]$DbPassword = $env:PAGW_DATABASE_PASSWORD ?? "pagw123"
)

$ErrorActionPreference = "Stop"

Write-Host "========================================"
Write-Host "PAGW Database Initialization"
Write-Host "========================================"
Write-Host "Host: ${DbHost}:${DbPort}"
Write-Host "Database: $DbName"
Write-Host ""

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$MigrationsDir = Join-Path $ScriptDir "..\db\migrations"

# Set PGPASSWORD for psql
$env:PGPASSWORD = $DbPassword

# Wait for database to be ready
Write-Host "Waiting for database to be ready..."
$ready = $false
for ($i = 1; $i -le 30; $i++) {
    try {
        $result = & psql -h $DbHost -p $DbPort -U $DbUser -d postgres -c "SELECT 1" 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Database is ready!"
            $ready = $true
            break
        }
    } catch {
        # Continue waiting
    }
    Start-Sleep -Seconds 1
}

if (-not $ready) {
    Write-Host "ERROR: Database not ready after 30 seconds"
    exit 1
}

# Create database if not exists
Write-Host "Creating database if not exists..."
$dbExists = & psql -h $DbHost -p $DbPort -U $DbUser -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = '$DbName'" 2>$null
if ($dbExists -notmatch "1") {
    & psql -h $DbHost -p $DbPort -U $DbUser -d postgres -c "CREATE DATABASE $DbName"
}

# Run migrations in order
Write-Host ""
Write-Host "Running migrations..."
$migrations = Get-ChildItem -Path $MigrationsDir -Filter "*.sql" | Sort-Object Name

foreach ($migration in $migrations) {
    Write-Host "  Running: $($migration.Name)"
    try {
        & psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -f $migration.FullName 2>&1 | Out-Null
    } catch {
        Write-Host "    Note: Migration may have already been applied"
    }
}

Write-Host ""
Write-Host "========================================"
Write-Host "Database initialization complete!"
Write-Host "========================================"

# Verify tables were created
Write-Host ""
Write-Host "Verifying tables..."
& psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -c "\dt pagw.*"
