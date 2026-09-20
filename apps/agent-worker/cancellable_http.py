"""Small stdlib HTTP transport that can abort an in-flight provider request.

The model workers cannot rely on process cancellation: one process may own several leased jobs
over its lifetime.  This transport therefore couples each request to the job lease and shuts down
the concrete socket when that lease is lost.  It deliberately does not implement redirects,
proxy discovery, cookies, retries, or connection pooling.
"""

from __future__ import annotations

import http.client
import socket
import ssl
import threading
import time
import urllib.parse
from dataclasses import dataclass


class RequestCancelled(Exception):
    """Raised only when the caller's cancellation event aborted the request."""


@dataclass(frozen=True)
class Response:
    status: int
    headers: http.client.HTTPMessage
    body: bytes


class CancellableHttpClient:
    def __init__(self, context: ssl.SSLContext):
        self.context = context

    def post(
        self,
        url: str,
        body: bytes,
        headers: dict[str, str],
        timeout_seconds: float,
        maximum_response_bytes: int,
        cancel: threading.Event | None = None,
    ) -> Response:
        parsed = urllib.parse.urlsplit(url)
        if (
            parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.fragment
        ):
            raise ValueError("HTTP endpoint is invalid")
        if cancel is not None and cancel.is_set():
            raise RequestCancelled()

        connection_type = (
            http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
        )
        connection_args = {"timeout": timeout_seconds}
        if parsed.scheme == "https":
            connection_args["context"] = self.context
        connection = connection_type(parsed.hostname, parsed.port, **connection_args)
        finished = threading.Event()
        watcher: threading.Thread | None = None

        def abort_when_cancelled() -> None:
            assert cancel is not None
            while not cancel.is_set():
                if finished.wait(0.05):
                    return
            # A cancellation can race DNS/connect. Wait until http.client publishes the socket,
            # then shutdown (not merely close) so a getresponse/read blocked in another thread
            # wakes immediately on all supported operating systems.
            while not finished.is_set():
                active_socket = connection.sock
                if active_socket is not None:
                    try:
                        active_socket.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    try:
                        active_socket.close()
                    except OSError:
                        pass
                    return
                time.sleep(0.01)

        if cancel is not None:
            watcher = threading.Thread(
                target=abort_when_cancelled,
                name="model-http-cancellation",
                daemon=True,
            )
            watcher.start()

        target = urllib.parse.urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
        try:
            connection.request("POST", target, body=body, headers=headers)
            response = connection.getresponse()
            raw = response.read(maximum_response_bytes + 1)
            if cancel is not None and cancel.is_set():
                raise RequestCancelled()
            return Response(response.status, response.headers, raw)
        except RequestCancelled:
            raise
        except (OSError, http.client.HTTPException) as error:
            if cancel is not None and cancel.is_set():
                raise RequestCancelled() from error
            raise
        finally:
            finished.set()
            connection.close()
            if watcher is not None:
                watcher.join(timeout=1)
