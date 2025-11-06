"""
Finance ML Service - Pydantic Models
Phase 2: Python ML Service
Date: 2025-11-06

Request/Response models for API endpoints.
Following Rich Hickey's principle: Data as the interface.
"""

from pydantic import BaseModel, Field
from typing import Optional, Literal
from datetime import date


# ============================================================================
# Request Models (Input Values)
# ============================================================================

class Transaction(BaseModel):
    """Transaction data for ML classification.

    This is a VALUE - immutable data sent from Clojure.
    """

    id: str = Field(..., description="Transaction ID")
    description: str = Field(..., description="Raw transaction description")
    amount: float = Field(..., description="Transaction amount")
    date: date = Field(..., description="Transaction date")
    bank: Optional[str] = Field(None, description="Bank name (optional)")

    class Config:
        json_schema_extra = {
            "example": {
                "id": "tx-12345",
                "description": "STARBUCKS #1234 SEATTLE WA",
                "amount": 4.99,
                "date": "2024-03-20",
                "bank": "bofa"
            }
        }


class BatchTransactionRequest(BaseModel):
    """Batch request for multiple transactions."""

    transactions: list[Transaction] = Field(..., description="List of transactions")

    class Config:
        json_schema_extra = {
            "example": {
                "transactions": [
                    {
                        "id": "tx-1",
                        "description": "STARBUCKS",
                        "amount": 4.99,
                        "date": "2024-03-20"
                    }
                ]
            }
        }


# ============================================================================
# Response Models (Output Values)
# ============================================================================

class MerchantDetection(BaseModel):
    """Merchant detection result.

    This is a VALUE - new data returned to Clojure.
    NOT stored yet - Clojure decides what to do with it.
    """

    transaction_id: str = Field(..., description="Transaction ID")
    merchant: str = Field(..., description="Detected merchant canonical name")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Confidence score (0.0-1.0)")
    model: str = Field(..., description="Model used for detection")
    reasoning: Optional[str] = Field(None, description="Why this merchant was detected")

    class Config:
        json_schema_extra = {
            "example": {
                "transaction_id": "tx-12345",
                "merchant": "starbucks",
                "confidence": 0.95,
                "model": "gpt-4-turbo-preview",
                "reasoning": "Description contains 'STARBUCKS' which matches canonical merchant"
            }
        }


class CategoryDetection(BaseModel):
    """Category detection result."""

    transaction_id: str = Field(..., description="Transaction ID")
    category: str = Field(..., description="Detected category canonical name")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Confidence score (0.0-1.0)")
    method: Literal["rule", "ml"] = Field(..., description="Detection method (rule or ML)")
    rule_id: Optional[str] = Field(None, description="Rule ID if rule-based")
    model: Optional[str] = Field(None, description="Model if ML-based")
    reasoning: Optional[str] = Field(None, description="Why this category was chosen")

    class Config:
        json_schema_extra = {
            "example": {
                "transaction_id": "tx-12345",
                "category": "cafe",
                "confidence": 0.98,
                "method": "rule",
                "rule_id": "starbucks-cafe-rule",
                "reasoning": "Merchant 'starbucks' matches rule #15: starbucks → cafe"
            }
        }


class AnomalyDetection(BaseModel):
    """Anomaly detection result."""

    transaction_id: str = Field(..., description="Transaction ID")
    is_anomaly: bool = Field(..., description="True if transaction is anomalous")
    anomaly_score: float = Field(..., description="Anomaly score (higher = more anomalous)")
    confidence: float = Field(..., ge=0.0, le=1.0, description="Confidence in anomaly detection")
    reasons: list[str] = Field(..., description="List of reasons why it's anomalous")

    class Config:
        json_schema_extra = {
            "example": {
                "transaction_id": "tx-12345",
                "is_anomaly": True,
                "anomaly_score": 3.5,
                "confidence": 0.92,
                "reasons": [
                    "Amount ($499.99) is 3.5 std devs above mean",
                    "Merchant 'UNKNOWN-STORE' not seen before",
                    "Transaction time (3:00 AM) unusual for this user"
                ]
            }
        }


class BatchDetectionResponse(BaseModel):
    """Batch detection response."""

    merchant_detections: list[MerchantDetection] = Field(default_factory=list)
    category_detections: list[CategoryDetection] = Field(default_factory=list)
    anomaly_detections: list[AnomalyDetection] = Field(default_factory=list)
    processed_count: int = Field(..., description="Number of transactions processed")
    failed_count: int = Field(..., description="Number of transactions that failed")
    errors: list[dict] = Field(default_factory=list, description="Errors if any")


# ============================================================================
# Health Check Model
# ============================================================================

class HealthResponse(BaseModel):
    """Health check response."""

    status: Literal["healthy", "degraded", "unhealthy"]
    version: str
    llm_provider: str
    llm_available: bool
    timestamp: str

    class Config:
        json_schema_extra = {
            "example": {
                "status": "healthy",
                "version": "v1.0.0",
                "llm_provider": "openai",
                "llm_available": True,
                "timestamp": "2024-03-20T10:30:00Z"
            }
        }
