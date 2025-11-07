# Deployment Guide - Finance Trust Construction v2.0

**Phase 4: Deployment**
**Date:** 2025-11-06
**Status:** Ready for deployment

---

## Overview

This system consists of 3 services:

1. **Datomic Free** - Immutable database
2. **Python ML Service** - FastAPI service for ML detection
3. **Clojure REST API** - Main API + ML orchestration

All services are containerized with Docker and orchestrated with Docker Compose.

---

## Prerequisites

- Docker Desktop installed (Docker Engine 20.10+)
- Docker Compose v2.0+
- 4GB RAM minimum
- (Optional) OpenAI API key for LLM-based merchant detection

---

## Quick Start

### 1. Clone Repository

```bash
git clone <repository-url>
cd finance-clj
```

### 2. Configure Environment (Optional)

```bash
cp .env.example .env
# Edit .env if you have OpenAI API key
```

### 3. Start All Services

```bash
docker-compose up -d
```

**Output:**
```
Creating network "finance-network" ...
Creating volume "finance-datomic-data" ...
Creating finance-datomic ...
Creating finance-python-ml ...
Creating finance-clojure-api ...
```

### 4. Verify Services

```bash
# Check all services are running
docker-compose ps

# Expected output:
NAME                  STATUS              PORTS
finance-datomic       Up (healthy)        0.0.0.0:4334->4334/tcp
finance-python-ml     Up (healthy)        0.0.0.0:8000->8000/tcp
finance-clojure-api   Up (healthy)        0.0.0.0:3000->3000/tcp
```

### 5. Test API

```bash
# Health check
curl http://localhost:3000/api/v1/health

# Expected output:
{
  "status": "healthy",
  "version": "v1.0",
  "timestamp": "2025-11-06T...",
  "database": {"connected": true}
}
```

---

## Service Details

### Datomic (Port 4334)

- **Image:** `akiel/datomic-free:0.9.5697`
- **Data:** Persisted in Docker volume `finance-datomic-data`
- **Health:** TCP check on port 4334

### Python ML Service (Port 8000)

- **Build:** `./ml-service/Dockerfile`
- **Endpoints:**
  - `GET /health` - Health check
  - `POST /v1/detect/merchant` - Merchant detection
  - `POST /v1/detect/category` - Category detection
  - `POST /v1/detect/anomaly` - Anomaly detection
- **Docs:** http://localhost:8000/docs (Swagger UI)

### Clojure API (Port 3000)

- **Build:** `./Dockerfile`
- **Endpoints:**
  - `GET /api/v1/health` - Health check
  - `GET /api/v1/transactions` - List transactions
  - `POST /api/v1/transactions/:id/classify` - Submit for ML classification
  - `GET /api/v1/review-queue` - Get pending reviews
  - `POST /api/v1/review-queue/:id/approve` - Approve classification
  - `POST /api/v1/review-queue/:id/reject` - Reject classification
  - `POST /api/v1/review-queue/:id/correct` - Correct classification

---

## Common Commands

### View Logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f clojure-api
docker-compose logs -f python-ml
docker-compose logs -f datomic
```

### Restart Services

```bash
# Restart all
docker-compose restart

# Restart specific service
docker-compose restart clojure-api
```

### Stop Services

```bash
# Stop but keep data
docker-compose down

# Stop and remove data
docker-compose down -v
```

### Rebuild After Code Changes

```bash
# Rebuild and restart
docker-compose up -d --build

# Rebuild specific service
docker-compose up -d --build clojure-api
```

---

## Troubleshooting

### Service Won't Start

**Check logs:**
```bash
docker-compose logs <service-name>
```

**Common issues:**
- Port already in use → Change port in `docker-compose.yml`
- Out of memory → Increase Docker memory limit
- Datomic not ready → Wait for health check to pass

### Clojure API Can't Connect to Datomic

**Symptoms:** Health check fails with database error

**Fix:**
```bash
# Restart Datomic first
docker-compose restart datomic

# Wait for health check
docker-compose ps

# Restart Clojure API
docker-compose restart clojure-api
```

### Python ML Service Errors

**Check if service is running:**
```bash
curl http://localhost:8000/health
```

**Restart:**
```bash
docker-compose restart python-ml
```

---

## Development Workflow

### Local Development (Without Docker)

**Datomic:**
```bash
# Use in-memory for development
export DATOMIC_URI="datomic:mem://dev"
```

**Python ML Service:**
```bash
cd ml-service
pip install -r requirements.txt
python main.py
# Runs on http://localhost:8000
```

**Clojure API:**
```bash
export ML_SERVICE_URL="http://localhost:8000"
export DATOMIC_URI="datomic:mem://dev"
clojure -M -m finance.api.core
# Runs on http://localhost:3000
```

### Hot Reload During Development

**Python (auto-reload enabled):**
```bash
# Edit code, service auto-reloads
```

**Clojure:**
```bash
# Stop container
docker-compose stop clojure-api

# Run locally with REPL
clojure -M:repl

# Or rebuild container
docker-compose up -d --build clojure-api
```

---

## Production Considerations

### Security

- [ ] Change default ports
- [ ] Add API authentication
- [ ] Use Datomic Pro with backups
- [ ] Set strong passwords for databases
- [ ] Enable HTTPS/TLS

### Monitoring

- [ ] Add Prometheus metrics
- [ ] Set up log aggregation (ELK, Splunk)
- [ ] Configure alerts for service health
- [ ] Track API response times

### Scaling

- [ ] Use Datomic Pro cluster
- [ ] Scale Clojure API horizontally (load balancer)
- [ ] Scale Python ML service (multiple replicas)
- [ ] Add Redis for caching

---

## Architecture Diagram

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │
       ↓
┌──────────────────────┐
│  Clojure REST API    │ :3000
│  - Routes            │
│  - ML Orchestration  │
│  - Review Queue      │
└──────┬───────┬───────┘
       │       │
       ↓       ↓
   ┌───────┐ ┌──────────────┐
   │Datomic│ │ Python ML    │ :8000
   │       │ │ - Merchant   │
   │ :4334 │ │ - Category   │
   └───────┘ │ - Anomaly    │
             └──────────────┘
```

---

## Next Steps

1. ✅ Deploy with Docker Compose
2. ⏳ Add integration tests
3. ⏳ Set up CI/CD pipeline
4. ⏳ Configure production database
5. ⏳ Add monitoring and alerts

---

## Support

**Issues:** Create GitHub issue
**Logs:** Check `docker-compose logs`
**Documentation:** See `PHASE_*_COMPLETE.md` files

---

**Status:** ✅ Ready for deployment
**Last Updated:** 2025-11-06
