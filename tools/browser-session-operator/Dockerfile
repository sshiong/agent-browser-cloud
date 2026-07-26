ARG PYTHON_BASE_IMAGE=python:3.13-alpine
FROM ${PYTHON_BASE_IMAGE}
WORKDIR /app
COPY browser_session_operator.py /app/browser_session_operator.py
USER 65532:65532
ENTRYPOINT ["python", "/app/browser_session_operator.py"]
