ARG PYTHON_BASE_IMAGE=python:3.13-alpine
FROM ${PYTHON_BASE_IMAGE}
WORKDIR /app
COPY application_adapter.py /app/application_adapter.py
USER 65532:65532
ENTRYPOINT ["python", "/app/application_adapter.py"]
