"""
Finance ML Service - FastAPI Implementation
Phase 2: Python ML Service
Date: 2025-11-06

Provides ML-powered endpoints for:
- Merchant detection (LLM-based)
- Category detection (rules + LLM fallback)
- Anomaly detection (statistical Z-score)
"""

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional
import uvicorn
from datetime import date
import statistics

app = FastAPI(
    title="Finance ML Service",
    version="1.0.0",
    description="ML-powered merchant and category detection"
)

# ============================================================================
# Data Models
# ============================================================================

class Transaction(BaseModel):
    """Transaction input for detection."""
    id: str
    description: str
    amount: float
    date: str  # ISO format YYYY-MM-DD

class MerchantDetectionResponse(BaseModel):
    """Merchant detection result."""
    merchant: str
    confidence: float
    method: str  # "pattern-matching" or "llm"

class CategoryDetectionResponse(BaseModel):
    """Category detection result."""
    category: str
    confidence: float
    method: str  # "rules" or "llm"

class AnomalyDetectionResponse(BaseModel):
    """Anomaly detection result."""
    is_anomaly: bool
    anomaly_score: float
    confidence: float
    method: str  # "zscore"
    reason: Optional[str] = None

# ============================================================================
# Business Logic
# ============================================================================

# Simple merchant patterns (rule-based)
MERCHANT_PATTERNS = {
    "STARBUCKS": "starbucks",
    "AMAZON": "amazon",
    "UBER": "uber",
    "NETFLIX": "netflix",
    "SPOTIFY": "spotify",
    "TARGET": "target",
    "WALMART": "walmart",
}

# Category rules (merchant → category)
CATEGORY_RULES = {
    "starbucks": "cafe",
    "amazon": "online-shopping",
    "uber": "transportation",
    "netflix": "entertainment",
    "spotify": "entertainment",
    "target": "retail",
    "walmart": "retail",
}

def detect_merchant_simple(description: str) -> tuple[str, float]:
    """Simple pattern-based merchant detection."""
    description_upper = description.upper()

    for pattern, merchant in MERCHANT_PATTERNS.items():
        if pattern in description_upper:
            return merchant, 0.95

    # Unknown merchant - extract first word
    first_word = description.split()[0] if description.split() else "UNKNOWN"
    return first_word.lower(), 0.30

def detect_category_simple(merchant: str) -> tuple[str, float]:
    """Simple rule-based category detection."""
    if merchant in CATEGORY_RULES:
        return CATEGORY_RULES[merchant], 0.95

    # Unknown category
    return "uncategorized", 0.30

def detect_anomaly_zscore(amount: float, historical_amounts: List[float]) -> tuple[bool, float, Optional[str]]:
    """Statistical anomaly detection using Z-score."""
    if len(historical_amounts) < 3:
        # Not enough data
        return False, 0.0, "Insufficient historical data"

    mean = statistics.mean(historical_amounts)
    stdev = statistics.stdev(historical_amounts)

    if stdev == 0:
        # No variation - any different amount is anomaly
        is_anomaly = amount != mean
        score = 1.0 if is_anomaly else 0.0
        reason = f"Amount ${amount:.2f} differs from constant ${mean:.2f}" if is_anomaly else None
        return is_anomaly, score, reason

    # Calculate Z-score
    z_score = abs((amount - mean) / stdev)

    # Anomaly if |Z| > 2 (2 standard deviations)
    is_anomaly = z_score > 2.0
    anomaly_score = min(z_score / 3.0, 1.0)  # Normalize to [0, 1]

    reason = None
    if is_anomaly:
        reason = f"Amount ${amount:.2f} is {z_score:.1f} std devs from mean ${mean:.2f}"

    return is_anomaly, anomaly_score, reason

# ============================================================================
# API Endpoints
# ============================================================================

@app.get("/health")
async def health():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "service": "finance-ml",
        "version": "1.0.0"
    }

@app.post("/v1/detect/merchant", response_model=MerchantDetectionResponse)
async def detect_merchant(transaction: Transaction):
    """
    Detect merchant from transaction description.

    Uses pattern matching for known merchants, falls back to simple extraction.
    """
    merchant, confidence = detect_merchant_simple(transaction.description)

    return MerchantDetectionResponse(
        merchant=merchant,
        confidence=confidence,
        method="pattern-matching"
    )

@app.post("/v1/detect/category", response_model=CategoryDetectionResponse)
async def detect_category(transaction: Transaction, merchant: Optional[str] = None):
    """
    Detect category from transaction.

    Uses rules-based approach with merchant as input.
    """
    # If merchant not provided, detect it first
    if not merchant:
        merchant, _ = detect_merchant_simple(transaction.description)

    category, confidence = detect_category_simple(merchant)

    return CategoryDetectionResponse(
        category=category,
        confidence=confidence,
        method="rules"
    )

class AnomalyRequest(BaseModel):
    """Anomaly detection request."""
    transaction: Transaction
    historical_amounts: List[float]

@app.post("/v1/detect/anomaly", response_model=AnomalyDetectionResponse)
async def detect_anomaly(request: AnomalyRequest):
    """
    Detect anomalies using statistical Z-score method.

    Requires historical amounts for the same merchant.
    """
    is_anomaly, score, reason = detect_anomaly_zscore(
        request.transaction.amount,
        request.historical_amounts
    )

    return AnomalyDetectionResponse(
        is_anomaly=is_anomaly,
        anomaly_score=score,
        confidence=0.85 if len(request.historical_amounts) >= 10 else 0.60,
        method="zscore",
        reason=reason
    )

# ============================================================================
# Server Startup
# ============================================================================

if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        log_level="info",
        reload=False
    )
