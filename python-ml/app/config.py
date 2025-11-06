"""
Finance ML Service - Configuration
Phase 2: Python ML Service
Date: 2025-11-06

Environment-based configuration using Pydantic Settings.
"""

from pydantic_settings import BaseSettings
from typing import Literal


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    # Service Info
    service_name: str = "finance-ml-service"
    service_version: str = "v1.0.0"

    # Server
    host: str = "0.0.0.0"
    port: int = 8000

    # LLM Configuration
    llm_provider: Literal["openai", "anthropic"] = "openai"
    openai_api_key: str = ""
    openai_model: str = "gpt-4-turbo-preview"
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-3-sonnet-20240229"

    # Detection Thresholds
    merchant_confidence_threshold: float = 0.70
    category_confidence_threshold: float = 0.70
    anomaly_threshold: float = 2.5  # Standard deviations

    # Logging
    log_level: str = "INFO"
    log_format: str = "json"  # json or text

    # CORS
    cors_origins: list[str] = ["http://localhost:3000", "http://localhost:5173"]

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


# Global settings instance
settings = Settings()
