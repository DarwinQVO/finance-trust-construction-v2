# Dockerfile for Clojure REST API
# Phase 4: Deployment
# Date: 2025-11-06
#
# Multi-stage build:
# 1. Builder: Compile Clojure code
# 2. Runtime: Run compiled application
#
# Usage:
#   docker build -t finance-clojure-api .
#   docker run -p 3000:3000 finance-clojure-api

# ============================================================================
# Stage 1: Builder
# ============================================================================
FROM clojure:temurin-21-tools-deps-1.11.1.1435-alpine AS builder

WORKDIR /app

# Copy deps.edn first (for layer caching)
COPY deps.edn .

# Download dependencies (cached layer)
RUN clojure -P

# Copy source code
COPY src ./src
COPY resources ./resources
COPY scripts ./scripts

# Optional: Run tests
# RUN clojure -M:test

# AOT compile (optional, for faster startup)
# RUN clojure -M -e "(compile 'finance.api.core)"

# ============================================================================
# Stage 2: Runtime
# ============================================================================
FROM clojure:temurin-21-tools-deps-1.11.1.1435-alpine

WORKDIR /app

# Install curl for healthcheck
RUN apk add --no-cache curl

# Copy dependencies and source from builder
COPY --from=builder /root/.m2 /root/.m2
COPY deps.edn .
COPY src ./src
COPY resources ./resources
COPY scripts ./scripts

# Environment variables
ENV DATOMIC_URI=datomic:free://datomic:4334/finance
ENV ML_SERVICE_URL=http://python-ml:8000
ENV PORT=3000
ENV LOG_LEVEL=info

# Expose port
EXPOSE 3000

# Health check
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \\
  CMD curl -f http://localhost:3000/api/v1/health || exit 1

# Run server
CMD ["clojure", "-M", "-m", "finance.api.core"]
