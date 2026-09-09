FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    ISM_GATEWAY_DB=/data/gateway_free.db \
    PUBLIC_BASE_URL=https://ism-free-gateway.fly.dev \
    REQUIRE_GATEWAY_TOKEN=false \
    FREE_MODE=true \
    PROVIDER_MODE=mock

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

COPY main.py .

RUN mkdir -p /data && chmod 777 /data

EXPOSE 8787
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8787"]
