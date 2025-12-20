# ============================================================================
# PAGW Microservices - Makefile
# ============================================================================

.PHONY: help build clean test all

help:
	@echo "PAGW Microservices - Build Commands"
	@echo "===================================="
	@echo ""
	@echo "  make build    - Build all services"
	@echo "  make clean    - Clean all build artifacts"
	@echo "  make test     - Run all tests"
	@echo "  make all      - Clean, build, and test"

build:
	mvn clean package -DskipTests

clean:
	mvn clean

test:
	mvn test

all: clean build test
