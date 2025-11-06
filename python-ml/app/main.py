"""
Finance ML Service - FastAPI Application
Phase 2: Python ML Service
Date: 2025-11-06

Main FastAPI app with structured logging and CORS.
"""

import structlog
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager

from app.config import settings
from app.api.v1 import endpoints

# Configure structured logging
structlog.configure(
    processors=[
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.stdlib.add_log_level,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.processors.JSONRenderer() if settings.log_format == "json"
        else structlog.dev.ConsoleRenderer(),
    ],
    logger_factory=structlog.PrintLoggerFactory(),
)

logger = structlog.get_logger()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan events."""
    # Startup
    logger.info(
        "service_starting",
        service=settings.service_name,
        version=settings.service_version,
        llm_provider=settings.llm_provider,
    )
    yield
    # Shutdown
    logger.info("service_stopping")


# Create FastAPI app
app = FastAPI(
    title="Finance ML Service",
    description="ML Service for transaction classification (Phase 2: Python ML Service)",
    version=settings.service_version,
    lifespan=lifespan,
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(endpoints.router)


@app.get("/")
async def root():
    """Root endpoint - redirect to docs."""
    return {
        "service": settings.service_name,
        "version": settings.service_version,
        "docs": "/docs",
        "health": "/v1/health",
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=True,
        log_level=settings.log_level.lower(),
    )
