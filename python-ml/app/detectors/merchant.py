"""
Finance ML Service - Merchant Detector
Phase 2: Python ML Service
Date: 2025-11-06

LLM-based merchant extraction following Rich Hickey's TRANSFORM pattern:
- Input: Transaction VALUE (description, amount, date)
- Output: MerchantDetection VALUE (merchant, confidence)
- NO state changes, NO database writes
- Pure transformation: value → new value
"""

import structlog
from openai import AsyncOpenAI
from anthropic import AsyncAnthropic
from typing import Literal

from app.config import settings
from app.models import Transaction, MerchantDetection

logger = structlog.get_logger()


class MerchantDetector:
    """Detect merchant from transaction description using LLM.

    Rich Hickey Pattern: TRANSFORM service
    - Stateless (no instance variables)
    - Pure function (same input → same output)
    - Returns new value, doesn't modify input
    """

    def __init__(self):
        """Initialize LLM clients."""
        self.provider: Literal["openai", "anthropic"] = settings.llm_provider

        if self.provider == "openai":
            self.openai_client = AsyncOpenAI(api_key=settings.openai_api_key)
            self.model = settings.openai_model
        else:
            self.anthropic_client = AsyncAnthropic(api_key=settings.anthropic_api_key)
            self.model = settings.anthropic_model

        logger.info(
            "merchant_detector_initialized",
            provider=self.provider,
            model=self.model,
        )

    async def detect(self, transaction: Transaction) -> MerchantDetection:
        """Detect merchant from transaction.

        Pure transformation:
        Transaction value → MerchantDetection value

        Args:
            transaction: Transaction data (immutable input)

        Returns:
            MerchantDetection: New value with merchant + confidence

        Raises:
            Exception: If LLM API call fails
        """
        logger.info(
            "merchant_detection_started",
            transaction_id=transaction.id,
            description=transaction.description,
        )

        try:
            # Call LLM based on provider
            if self.provider == "openai":
                result = await self._detect_with_openai(transaction)
            else:
                result = await self._detect_with_anthropic(transaction)

            logger.info(
                "merchant_detection_completed",
                transaction_id=transaction.id,
                merchant=result.merchant,
                confidence=result.confidence,
            )

            return result

        except Exception as e:
            logger.error(
                "merchant_detection_failed",
                transaction_id=transaction.id,
                error=str(e),
            )
            raise

    async def _detect_with_openai(
        self, transaction: Transaction
    ) -> MerchantDetection:
        """Detect merchant using OpenAI GPT-4."""

        prompt = self._build_prompt(transaction)

        response = await self.openai_client.chat.completions.create(
            model=self.model,
            messages=[
                {
                    "role": "system",
                    "content": "You are a financial transaction analyzer. Extract the merchant name from transaction descriptions and provide a canonical merchant name (lowercase, no spaces).",
                },
                {"role": "user", "content": prompt},
            ],
            temperature=0.0,  # Deterministic
            response_format={"type": "json_object"},
        )

        # Parse LLM response
        import json

        result = json.loads(response.choices[0].message.content)

        return MerchantDetection(
            transaction_id=transaction.id,
            merchant=result["merchant"],
            confidence=result["confidence"],
            model=self.model,
            reasoning=result.get("reasoning"),
        )

    async def _detect_with_anthropic(
        self, transaction: Transaction
    ) -> MerchantDetection:
        """Detect merchant using Anthropic Claude."""

        prompt = self._build_prompt(transaction)

        response = await self.anthropic_client.messages.create(
            model=self.model,
            max_tokens=1024,
            messages=[{"role": "user", "content": prompt}],
        )

        # Parse Claude response
        import json

        content = response.content[0].text
        result = json.loads(content)

        return MerchantDetection(
            transaction_id=transaction.id,
            merchant=result["merchant"],
            confidence=result["confidence"],
            model=self.model,
            reasoning=result.get("reasoning"),
        )

    def _build_prompt(self, transaction: Transaction) -> str:
        """Build LLM prompt for merchant detection."""

        return f"""Extract the merchant from this transaction description and return JSON:

Transaction:
- Description: "{transaction.description}"
- Amount: ${transaction.amount}
- Date: {transaction.date}

Return JSON with this exact structure:
{{
  "merchant": "canonical_merchant_name",
  "confidence": 0.95,
  "reasoning": "Why you chose this merchant"
}}

Rules:
1. merchant should be lowercase, no spaces (use underscore)
2. confidence is 0.0-1.0 (how sure you are)
3. Common merchants: starbucks, amazon, walmart, target, apple, uber, etc.
4. If unknown, use "unknown_merchant" with low confidence
5. Be consistent: "STARBUCKS #1234" → "starbucks"

Examples:
- "STARBUCKS #1234 SEATTLE WA" → {{"merchant": "starbucks", "confidence": 0.98}}
- "AMAZON MKTPLACE" → {{"merchant": "amazon", "confidence": 0.95}}
- "SQ *COFFEE SHOP" → {{"merchant": "unknown_merchant", "confidence": 0.30}}

Now extract the merchant from the transaction above:"""


# ============================================================================
# Helper: Batch Detection
# ============================================================================


async def detect_merchants_batch(
    transactions: list[Transaction],
) -> list[MerchantDetection]:
    """Detect merchants for multiple transactions.

    Uses asyncio.gather for parallel LLM calls.

    Args:
        transactions: List of transactions

    Returns:
        List of merchant detections (same order as input)
    """
    import asyncio

    detector = MerchantDetector()

    # Parallel LLM calls
    tasks = [detector.detect(tx) for tx in transactions]
    results = await asyncio.gather(*tasks, return_exceptions=True)

    # Filter out exceptions, log errors
    detections = []
    for i, result in enumerate(results):
        if isinstance(result, Exception):
            logger.error(
                "batch_detection_error",
                transaction_id=transactions[i].id,
                error=str(result),
            )
        else:
            detections.append(result)

    return detections
