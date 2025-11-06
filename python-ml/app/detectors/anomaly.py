"""Anomaly Detector - Statistical analysis"""

import structlog
import numpy as np
from app.config import settings
from app.models import Transaction, AnomalyDetection

logger = structlog.get_logger()


class AnomalyDetector:
    """Detect anomalies using statistical methods (Z-score)."""

    def __init__(self):
        self.threshold = settings.anomaly_threshold  # Standard deviations

    async def detect(
        self,
        transaction: Transaction,
        historical_amounts: list[float],
    ) -> AnomalyDetection:
        """Detect if transaction amount is anomalous.

        Args:
            transaction: Transaction to check
            historical_amounts: Historical amounts for this merchant/category

        Returns:
            AnomalyDetection with is_anomaly + score + reasons
        """

        logger.info(
            "anomaly_detection_started",
            transaction_id=transaction.id,
            amount=transaction.amount,
        )

        if not historical_amounts or len(historical_amounts) < 3:
            # Not enough data
            return AnomalyDetection(
                transaction_id=transaction.id,
                is_anomaly=False,
                anomaly_score=0.0,
                confidence=0.10,
                reasons=["Not enough historical data (need >= 3 transactions)"],
            )

        # Calculate Z-score
        mean = np.mean(historical_amounts)
        std = np.std(historical_amounts)

        if std == 0:
            # All amounts are the same
            return AnomalyDetection(
                transaction_id=transaction.id,
                is_anomaly=False,
                anomaly_score=0.0,
                confidence=0.50,
                reasons=["All historical amounts are identical"],
            )

        z_score = abs((transaction.amount - mean) / std)

        is_anomaly = z_score > self.threshold
        confidence = min(0.99, 0.50 + (z_score / 10))  # Higher z-score = higher confidence

        reasons = []
        if is_anomaly:
            reasons.append(
                f"Amount ${transaction.amount:.2f} is {z_score:.1f} std devs from mean ${mean:.2f}"
            )
            if transaction.amount > mean + (self.threshold * std):
                reasons.append(f"Unusually HIGH amount (threshold: {self.threshold} std devs)")
            else:
                reasons.append(f"Unusually LOW amount (threshold: {self.threshold} std devs)")

        logger.info(
            "anomaly_detection_completed",
            transaction_id=transaction.id,
            is_anomaly=is_anomaly,
            z_score=z_score,
        )

        return AnomalyDetection(
            transaction_id=transaction.id,
            is_anomaly=is_anomaly,
            anomaly_score=z_score,
            confidence=confidence,
            reasons=reasons,
        )
