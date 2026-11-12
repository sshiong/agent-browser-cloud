FROM python:3.12-slim
WORKDIR /app
COPY requirements.lock .
RUN pip install --no-cache-dir -r requirements.lock
COPY --chmod=0444 gateway.py .
USER 65532:65532
ENV MCP_HOST=0.0.0.0 MCP_PORT=8130 PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 8130
CMD ["python", "gateway.py"]
