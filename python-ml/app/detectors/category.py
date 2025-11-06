"""Category Detector - Rule-based + LLM fallback"""

import structlog
from openai import AsyncOpenAI
from anthropic import AsyncAnthropic

from app.config import settings
from app.models import Transaction, CategoryDetection

logger = structlog.get_logger()

# Rules as data (loaded from Clojure's merchant-rules.edn conceptually)
CATEGORY_RULES = {
    "starbucks": {"category": "cafe", "confidence": 0.98, "rule_id": "starbucks-rule"},
    "amazon": {"category": "shopping", "confidence": 0.95, "rule_id": "amazon-rule"},
    "uber": {"category": "transportation", "confidence": 0.95, "rule_id": "uber-rule"},
    "walmart": {"category": "groceries", "confidence": 0.90, "rule_id": "walmart-rule"},
    # Add more rules...
}


class CategoryDetector:
    """Detect category: try rules first, fallback to LLM."""

    def __init__(self):
        self.provider = settings.llm_provider
        if self.provider == "openai":
            self.openai_client = AsyncOpenAI(api_key=settings.openai_api_key)
            self.model = settings.openai_model
        else:
            self.anthropic_client = AsyncAnthropic(api_key=settings.anthropic_api_key)
            self.model = settings.anthropic_model

    async def detect(
        self, transaction: Transaction, merchant: str
    ) -> CategoryDetection:
        """Detect category from transaction + merchant."""

        logger.info(
            "category_detection_started",
            transaction_id=transaction.id,
            merchant=merchant,
        )

        # Try rule-based first
        if merchant in CATEGORY_RULES:
            rule = CATEGORY_RULES[merchant]
            logger.info(
                "category_detection_rule_match",
                transaction_id=transaction.id,
                rule_id=rule["rule_id"],
            )
            return CategoryDetection(
                transaction_id=transaction.id,
                category=rule["category"],
                confidence=rule["confidence"],
                method="rule",
                rule_id=rule["rule_id"],
                reasoning=f"Merchant '{merchant}' matches rule {rule['rule_id']}",
            )

        # Fallback to LLM
        logger.info(
            "category_detection_llm_fallback", transaction_id=transaction.id
        )
        return await self._detect_with_llm(transaction, merchant)

    async def _detect_with_llm(
        self, transaction: Transaction, merchant: str
    ) -> CategoryDetection:
        """Detect category using LLM."""

        prompt = f"""Classify this transaction into a category:

Transaction:
- Merchant: {merchant}
- Description: {transaction.description}
- Amount: ${transaction.amount}

Return JSON:
{{
  "category": "category_name",
  "confidence": 0.85,
  "reasoning": "Why this category"
}}

Categories: cafe, groceries, shopping, transportation, utilities, dining, entertainment, healthcare, other

Only use categories from the list above."""

        if self.provider == "openai":
            response = await self.openai_client.chat.completions.create(
                model=self.model,
                messages=[
                    {
                        "role": "system",
                        "content": "You are a transaction categorization expert.",
                    },
                    {"role": "user", "content": prompt},
                ],
                temperature=0.0,
                response_format={"type": "json_object"},
            )
            import json

            result = json.loads(response.choices[0].message.content)
        else:
            response = await self.anthropic_client.messages.create(
                model=self.model,
                max_tokens=1024,
                messages=[{"role": "user", "content": prompt}],
            )
            import json

            result = json.loads(response.content[0].text)

        return CategoryDetection(
            transaction_id=transaction.id,
            category=result["category"],
            confidence=result["confidence"],
            method="ml",
            model=self.model,
            reasoning=result.get("reasoning"),
        )
