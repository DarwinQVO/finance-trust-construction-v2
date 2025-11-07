# Dockerfile for Python ML Service
# Phase 2: Python ML Service
# Date: 2025-11-06
#
# Usage:
#   docker build -t finance-python-ml .
#   docker run -p 8000:8000 finance-python-ml

FROM python:3.11-slim

WORKDIR /app

# Install curl for healthcheck
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Copy requirements first (for layer caching)
COPY requirements.txt .

# Install dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY main.py .

# Environment variables
ENV PYTHONUNBUFFERED=1
ENV LOG_LEVEL=info

# Expose port
EXPOSE 8000

# Health check
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \\
  CMD curl -f http://localhost:8000/health || exit 1

# Run server
CMD ["python", "main.py"]
