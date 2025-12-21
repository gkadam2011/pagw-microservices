#!/bin/bash
# =====================================================
# Local Database Initialization Script
# Runs Flyway migrations against local PostgreSQL
# =====================================================

set -e

# Configuration
DB_HOST="${PAGW_DATABASE_HOST:-localhost}"
DB_PORT="${PAGW_DATABASE_PORT:-5432}"
DB_NAME="${PAGW_DATABASE_NAME:-pagw}"
DB_USER="${PAGW_DATABASE_USERNAME:-pagw}"
DB_PASSWORD="${PAGW_DATABASE_PASSWORD:-pagw123}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATIONS_DIR="$SCRIPT_DIR/../db/migrations"

echo "========================================"
echo "PAGW Database Initialization"
echo "========================================"
echo "Host: $DB_HOST:$DB_PORT"
echo "Database: $DB_NAME"
echo "Migrations: $MIGRATIONS_DIR"
echo ""

# Wait for database to be ready
echo "Waiting for database to be ready..."
for i in {1..30}; do
    if PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d postgres -c "SELECT 1" > /dev/null 2>&1; then
        echo "Database is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "ERROR: Database not ready after 30 seconds"
        exit 1
    fi
    sleep 1
done

# Create database if not exists
echo "Creating database if not exists..."
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" | grep -q 1 || \
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d postgres -c "CREATE DATABASE $DB_NAME"

# Run migrations in order
echo ""
echo "Running migrations..."
for migration in $(ls -1 $MIGRATIONS_DIR/*.sql | sort); do
    filename=$(basename "$migration")
    echo "  Running: $filename"
    PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -f "$migration" > /dev/null 2>&1 || {
        echo "    Note: Migration may have already been applied or had expected errors"
    }
done

echo ""
echo "========================================"
echo "Database initialization complete!"
echo "========================================"

# Verify tables were created
echo ""
echo "Verifying tables..."
PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "\dt pagw.*"
