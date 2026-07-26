ARG PYTHON_BASE_IMAGE=python:3.13-alpine
FROM ${PYTHON_BASE_IMAGE}
WORKDIR /app
COPY mock-control-plane.py /app/mock-control-plane.py
USER 65532:65532
ENTRYPOINT ["python", "/app/mock-control-plane.py"]
