"""
Finance ML Service - API v1 Endpoints
Phase 2: Python ML Service
Date: 2025-11-06

TRANSFORM service endpoints following Rich Hickey pattern:
- Receive value (Transaction)
- Return new value (Detection)
- NO database writes, NO state changes
"""

import structlog
from fastapi import APIRouter, HTTPException
from datetime import datetime

from app.models import (
    Transaction,
    MerchantDetection,
    CategoryDetection,
    AnomalyDetection,
    HealthResponse,
)
from app.detectors import MerchantDetector, CategoryDetector, AnomalyDetector
from app.config import settings

logger = structlog.get_logger()

router = APIRouter(prefix="/v1", tags=["v1"])

# Initialize detectors (singleton pattern)
merchant_detector = MerchantDetector()
category_detector = CategoryDetector()
anomaly_detector = AnomalyDetector()


@router.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint.

    Returns service status + LLM availability.
    """
    llm_available = bool(
        settings.openai_api_key or settings.anthropic_api_key
    )

    status = "healthy" if llm_available else "degraded"

    return HealthResponse(
        status=status,
        version=settings.service_version,
        llm_provider=settings.llm_provider,
        llm_available=llm_available,
        timestamp=datetime.utcnow().isoformat() + "Z",
    )


@router.post("/detect/merchant", response_model=MerchantDetection)
async def detect_merchant(transaction: Transaction):
    """Detect merchant from transaction.

    TRANSFORM service:
    - Input: Transaction value
    - Output: MerchantDetection value
    - NO side effects
    """
    try:
        logger.info(
            "merchant_detection_request",
            transaction_id=transaction.id,
        )

        result = await merchant_detector.detect(transaction)

        logger.info(
            "merchant_detection_success",
            transaction_id=transaction.id,
            merchant=result.merchant,
            confidence=result.confidence,
        )

        return result

    except Exception as e:
        logger.error(
            "merchant_detection_error",
            transaction_id=transaction.id,
            error=str(e),
        )
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/detect/category", response_model=CategoryDetection)
async def detect_category(
    transaction: Transaction,
    merchant: str,
):
    """Detect category from transaction + merchant.

    TRANSFORM service:
    - Input: Transaction value + merchant
    - Output: CategoryDetection value
    - Tries rules first, fallback to LLM
    """
    try:
        logger.info(
            "category_detection_request",
            transaction_id=transaction.id,
            merchant=merchant,
        )

        result = await category_detector.detect(transaction, merchant)

        logger.info(
            "category_detection_success",
            transaction_id=transaction.id,
            category=result.category,
            method=result.method,
        )

        return result

    except Exception as e:
        logger.error(
            "category_detection_error",
            transaction_id=transaction.id,
            error=str(e),
        )
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/detect/anomaly", response_model=AnomalyDetection)
async def detect_anomaly(
    transaction: Transaction,
    historical_amounts: list[float],
):
    """Detect if transaction is anomalous.

    TRANSFORM service:
    - Input: Transaction value + historical amounts
    - Output: AnomalyDetection value
    - Uses Z-score statistical analysis
    """
    try:
        logger.info(
            "anomaly_detection_request",
            transaction_id=transaction.id,
            historical_count=len(historical_amounts),
        )

        result = await anomaly_detector.detect(transaction, historical_amounts)

        logger.info(
            "anomaly_detection_success",
            transaction_id=transaction.id,
            is_anomaly=result.is_anomaly,
            score=result.anomaly_score,
        )

        return result

    except Exception as e:
        logger.error(
            "anomaly_detection_error",
            transaction_id=transaction.id,
            error=str(e),
        )
        raise HTTPException(status_code=500, detail=str(e))
