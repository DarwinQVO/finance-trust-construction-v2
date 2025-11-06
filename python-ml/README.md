# 🐍 Finance ML Service

**Phase 2: Python ML Service**
**Date:** 2025-11-06
**Version:** v1.0.0

ML Service for transaction classification following Rich Hickey's TRANSFORM pattern.

---

## 🎯 Architecture

**Pattern:** TRANSFORM service (Pure functions at service scale)

```
Input:  Transaction VALUE (immutable)
  ↓
[TRANSFORM] Python ML Service
  ↓
Output: Detection VALUE (new, immutable)

NO database writes, NO state changes
Clojure owns persistence decisions
```

---

## 🏭 3 Detectors

### 1. MerchantDetector (LLM-based)
- **Input:** Transaction description
- **Output:** Merchant + confidence
- **Tech:** OpenAI GPT-4 or Anthropic Claude
- **Pattern:** Pure LLM inference

### 2. CategoryDetector (Rule-based + LLM)
- **Input:** Transaction + merchant
- **Output:** Category + confidence
- **Tech:** Rules first, LLM fallback
- **Pattern:** Hybrid (rules as data)

### 3. AnomalyDetector (Statistical)
- **Input:** Transaction + historical amounts
- **Output:** Is anomaly + score
- **Tech:** Z-score analysis
- **Pattern:** Pure math

---

## 🚀 Quick Start

### 1. Setup Environment

```bash
cd python-ml

# Create virtualenv
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Configure environment
cp .env.example .env
# Edit .env with your API keys
```

### 2. Run Locally

```bash
# Development mode (with reload)
python -m app.main

# Or with uvicorn
uvicorn app.main:app --reload --port 8000
```

Server runs on: `http://localhost:8000`

---

## 📡 API Endpoints

### Health Check
```bash
curl http://localhost:8000/v1/health
```

Response:
```json
{
  "status": "healthy",
  "version": "v1.0.0",
  "llm_provider": "openai",
  "llm_available": true,
  "timestamp": "2024-03-20T10:00:00Z"
}
```

---

### Detect Merchant
```bash
curl -X POST http://localhost:8000/v1/detect/merchant \
  -H "Content-Type: application/json" \
  -d '{
    "id": "tx-12345",
    "description": "STARBUCKS #1234 SEATTLE WA",
    "amount": 4.99,
    "date": "2024-03-20"
  }'
```

Response:
```json
{
  "transaction_id": "tx-12345",
  "merchant": "starbucks",
  "confidence": 0.98,
  "model": "gpt-4-turbo-preview",
  "reasoning": "Description contains 'STARBUCKS'"
}
```

---

### Detect Category
```bash
curl -X POST "http://localhost:8000/v1/detect/category?merchant=starbucks" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "tx-12345",
    "description": "STARBUCKS #1234",
    "amount": 4.99,
    "date": "2024-03-20"
  }'
```

Response:
```json
{
  "transaction_id": "tx-12345",
  "category": "cafe",
  "confidence": 0.98,
  "method": "rule",
  "rule_id": "starbucks-rule",
  "reasoning": "Merchant 'starbucks' matches rule"
}
```

---

### Detect Anomaly
```bash
curl -X POST http://localhost:8000/v1/detect/anomaly \
  -H "Content-Type: application/json" \
  -d '{
    "transaction": {
      "id": "tx-12345",
      "description": "STARBUCKS",
      "amount": 499.99,
      "date": "2024-03-20"
    },
    "historical_amounts": [4.99, 5.50, 4.25, 6.00, 5.75]
  }'
```

Response:
```json
{
  "transaction_id": "tx-12345",
  "is_anomaly": true,
  "anomaly_score": 3.5,
  "confidence": 0.92,
  "reasons": [
    "Amount $499.99 is 3.5 std devs above mean $5.30"
  ]
}
```

---

## 🐳 Docker

### Build Image
```bash
docker build -t finance-ml-service .
```

### Run Container
```bash
docker run -p 8000:8000 \
  -e OPENAI_API_KEY=sk-... \
  -e LLM_PROVIDER=openai \
  finance-ml-service
```

---

## 🧪 Testing

```bash
# Run tests
pytest

# With coverage
pytest --cov=app
```

---

## 📊 Structured Logging

All logs use structured format (JSON) matching Clojure's Timbre:

```json
{
  "timestamp": "2024-03-20T10:00:00Z",
  "level": "info",
  "event": "merchant_detection_completed",
  "transaction_id": "tx-12345",
  "merchant": "starbucks",
  "confidence": 0.98
}
```

---

## 🔐 Configuration

See [.env.example](.env.example) for all configuration options.

**Key settings:**
- `LLM_PROVIDER`: "openai" or "anthropic"
- `OPENAI_API_KEY`: Your OpenAI API key
- `MERCHANT_CONFIDENCE_THRESHOLD`: 0.70 (default)
- `LOG_FORMAT`: "json" or "text"

---

## 🏗️ Project Structure

```
python-ml/
├── app/
│   ├── __init__.py
│   ├── main.py              # FastAPI app
│   ├── config.py            # Settings
│   ├── models.py            # Pydantic models
│   ├── detectors/
│   │   ├── merchant.py      # MerchantDetector
│   │   ├── category.py      # CategoryDetector
│   │   └── anomaly.py       # AnomalyDetector
│   └── api/v1/
│       └── endpoints.py     # API routes
├── tests/
├── requirements.txt
├── Dockerfile
└── README.md
```

---

## 🎯 Rich Hickey Principles

**✅ TRANSFORM Service Pattern:**
- Stateless (no instance variables)
- Pure functions (same input → same output)
- Returns new value, doesn't modify input
- NO database writes (Clojure owns persistence)

**✅ Data as the Interface:**
- Pydantic models for type safety
- JSON for interop with Clojure
- Explicit confidence scores

**✅ Decomplecting:**
- 3 detectors, each focused on ONE task
- No detector depends on another
- Each testable in isolation

---

## 📚 Next Steps

**Phase 3: Integration**
- Clojure HTTP client (clj-http)
- core.async orchestration
- Error handling + retries
- Circuit breaker pattern

**Phase 4: Documentation**
- OpenAPI schema
- Architecture diagrams
- Deployment guide

---

**Generated:** 2025-11-06
**Finance Trust Construction v2.0 - Phase 2 Complete**
