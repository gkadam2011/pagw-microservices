#!/bin/bash
# ============================================================================
# Sync script: pagw-microservices → ncr_aedleks_pagw_app
# ============================================================================
# Purpose: Sync microservice folders from development repo to Tekton deployment repo
# Usage: ./sync-to-tekton-repo.sh [service-name]
#   - No args: Syncs all services
#   - With service name: Syncs only that service (e.g., ./sync-to-tekton-repo.sh pasorchestrator)
# ============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Source and destination repo paths (adjust these for your Mac)
SOURCE_REPO="$HOME/pagw-microservices"
DEST_REPO="$HOME/ncr_aedleks_pagw_app"

# Services to sync (microservice directories)
SERVICES=(
    "outboxpublisher"
    "pasapiconnector"
    "pasattachmenthandler"
    "pasbusinessvalidator"
    "pascallbackhandler"
    "pasmockpayer"
    "pasorchestrator"
    "pasproviderauth"
    "pasrequestconverter"
    "pasrequestenricher"
    "pasrequestparser"
    "pasresponsebuilder"
    "passubscriptionhandler"
)

# Shared folders to sync
SHARED_FOLDERS=(
    "db"
    "docs"
    "infra"
    "META-INF"
    "scripts"
)

# Files to sync at root level
ROOT_FILES=(
    "pom.xml"
    "Makefile"
    "docker-compose.infra.yml"
    "docker-compose.local.yml"
    "docker-compose.services.yml"
    "Dockerfile.local"
    "README.md"
)

# ============================================================================
# Validation
# ============================================================================

if [ ! -d "$SOURCE_REPO" ]; then
    echo -e "${RED}Error: Source repo not found at $SOURCE_REPO${NC}"
    echo "Please update SOURCE_REPO path in the script"
    exit 1
fi

if [ ! -d "$DEST_REPO" ]; then
    echo -e "${RED}Error: Destination repo not found at $DEST_REPO${NC}"
    echo "Please update DEST_REPO path in the script"
    exit 1
fi

if [ ! -d "$SOURCE_REPO/.git" ]; then
    echo -e "${RED}Error: $SOURCE_REPO is not a git repository${NC}"
    exit 1
fi

if [ ! -d "$DEST_REPO/.git" ]; then
    echo -e "${RED}Error: $DEST_REPO is not a git repository${NC}"
    exit 1
fi

# ============================================================================
# Functions
# ============================================================================

sync_service() {
    local service=$1
    echo -e "${YELLOW}Syncing $service...${NC}"
    
    # Use rsync to sync, excluding .git directories
    rsync -av --delete \
        --exclude='.git' \
        --exclude='target/' \
        --exclude='node_modules/' \
        --exclude='.DS_Store' \
        "$SOURCE_REPO/$service/" "$DEST_REPO/$service/"
    
    echo -e "${GREEN}✓ $service synced${NC}"
}

sync_folder() {
    local folder=$1
    echo -e "${YELLOW}Syncing folder $folder...${NC}"
    
    rsync -av --delete \
        --exclude='.git' \
        --exclude='target/' \
        --exclude='*.log' \
        --exclude='.DS_Store' \
        "$SOURCE_REPO/$folder/" "$DEST_REPO/$folder/"
    
    echo -e "${GREEN}✓ $folder synced${NC}"
}

sync_file() {
    local file=$1
    echo -e "${YELLOW}Syncing file $file...${NC}"
    
    cp "$SOURCE_REPO/$file" "$DEST_REPO/$file"
    
    echo -e "${GREEN}✓ $file synced${NC}"
}

# ============================================================================
# Main Sync Logic
# ============================================================================

echo "============================================================================"
echo "  Syncing: $SOURCE_REPO"
echo "       to: $DEST_REPO"
echo "============================================================================"
echo ""

# Check if specific service requested
if [ $# -eq 1 ]; then
    SERVICE_NAME=$1
    
    # Check if it's a valid service
    if [[ " ${SERVICES[@]} " =~ " ${SERVICE_NAME} " ]]; then
        echo -e "${GREEN}Syncing single service: $SERVICE_NAME${NC}"
        sync_service "$SERVICE_NAME"
    else
        echo -e "${RED}Error: Unknown service '$SERVICE_NAME'${NC}"
        echo "Available services:"
        printf '%s\n' "${SERVICES[@]}"
        exit 1
    fi
else
    # Sync all services
    echo -e "${GREEN}Syncing all microservices...${NC}"
    echo ""
    
    for service in "${SERVICES[@]}"; do
        if [ -d "$SOURCE_REPO/$service" ]; then
            sync_service "$service"
        else
            echo -e "${YELLOW}⚠ Skipping $service (not found in source)${NC}"
        fi
    done
    
    echo ""
    echo -e "${GREEN}Syncing shared folders...${NC}"
    echo ""
    
    for folder in "${SHARED_FOLDERS[@]}"; do
        if [ -d "$SOURCE_REPO/$folder" ]; then
            sync_folder "$folder"
        else
            echo -e "${YELLOW}⚠ Skipping $folder (not found in source)${NC}"
        fi
    done
    
    echo ""
    echo -e "${GREEN}Syncing root files...${NC}"
    echo ""
    
    for file in "${ROOT_FILES[@]}"; do
        if [ -f "$SOURCE_REPO/$file" ]; then
            sync_file "$file"
        else
            echo -e "${YELLOW}⚠ Skipping $file (not found in source)${NC}"
        fi
    done
fi

echo ""
echo "============================================================================"
echo -e "${GREEN}✓ Sync completed successfully!${NC}"
echo "============================================================================"
echo ""
echo "Next steps:"
echo "  1. cd $DEST_REPO"
echo "  2. git status  # Review changes"
echo "  3. git add .   # Stage changes"
echo "  4. git commit -m 'sync: update from pagw-microservices'"
echo "  5. git push    # Push to Tekton repo"
echo ""
