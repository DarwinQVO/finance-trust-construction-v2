"""
Finance ML Service - Detectors Package
Phase 2: Python ML Service
Date: 2025-11-06

3 Detectors implementing TRANSFORM service pattern:
1. MerchantDetector - LLM-based merchant extraction
2. CategoryDetector - Rule-based + LLM category classification
3. AnomalyDetector - Statistical anomaly detection
"""

from .merchant import MerchantDetector
from .category import CategoryDetector
from .anomaly import AnomalyDetector

__all__ = ["MerchantDetector", "CategoryDetector", "AnomalyDetector"]
