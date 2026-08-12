#!/usr/bin/env python3
"""Generate deterministic dependency-light Python, Go and Java SDK surfaces.

The input must be a JSON bundle of the authoritative OpenAPI document. Redocly performs the
YAML-to-JSON parse before this script runs, so this generator never maintains a second parser.
"""

from __future__ import annotations

import hashlib
import json
import keyword
import pathlib
import re
import subprocess
import sys
from typing import Any, Iterable


GENERATOR = "browsercloud-multilang-generator@1"
HTTP_METHODS = ("get", "post", "put", "patch", "delete", "options", "head", "trace")


def fail(message: str) -> None:
    raise SystemExit(message)


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ref_name(schema: dict[str, Any] | None) -> str | None:
    if not schema:
        return None
    reference = schema.get("$ref")
    if isinstance(reference, str) and reference.startswith("#/components/schemas/"):
        return reference.rsplit("/", 1)[-1]
    return None


def nullable_ref_name(schema: dict[str, Any] | None) -> str | None:
    """Return the named member of an exact `Reference | null` union."""
    if not schema:
        return None
    members = schema.get("oneOf") or schema.get("anyOf")
    if not isinstance(members, list) or len(members) != 2:
        return None
    references = [ref_name(member) for member in members if isinstance(member, dict)]
    nulls = [
        member
        for member in members
        if isinstance(member, dict) and member.get("type") == "null"
    ]
    names = [name for name in references if name]
    return names[0] if len(names) == 1 and len(nulls) == 1 else None


def schema_label(schema: dict[str, Any] | None) -> str:
    name = ref_name(schema)
    if name:
        return name
    if not schema:
        return ""
    if schema.get("type") == "array":
        item = schema_label(schema.get("items"))
        return f"array<{item or 'any'}>"
    return str(schema.get("type", "any"))


def resolve_local(document: dict[str, Any], value: dict[str, Any]) -> dict[str, Any]:
    reference = value.get("$ref")
    if not isinstance(reference, str):
        return value
    if not reference.startswith("#/"):
        fail(f"external OpenAPI reference is not supported: {reference}")
    resolved: Any = document
    for segment in reference[2:].split("/"):
        resolved = resolved[segment.replace("~1", "/").replace("~0", "~")]
    if not isinstance(resolved, dict):
        fail(f"OpenAPI reference is not an object: {reference}")
    return resolved


def response_schema(
    document: dict[str, Any], operation: dict[str, Any]
) -> dict[str, Any] | None:
    responses = operation.get("responses", {})
    selected: dict[str, Any] | None = None
    for status, response in responses.items():
        if str(status).startswith("2"):
            selected = resolve_local(document, response)
            break
    if selected is None:
        fallback = responses.get("default")
        selected = resolve_local(document, fallback) if fallback else None
    if not selected:
        return None
    content = selected.get("content", {})
    for media_type in ("application/json", "text/event-stream", "application/octet-stream"):
        if media_type in content:
            return content[media_type].get("schema")
    return next((value.get("schema") for value in content.values()), None)


def request_schema(
    document: dict[str, Any], operation: dict[str, Any]
) -> tuple[dict[str, Any] | None, bool]:
    request = operation.get("requestBody") or {}
    request = resolve_local(document, request)
    content = request.get("content", {})
    for media_type in ("application/json", "application/octet-stream"):
        if media_type in content:
            return content[media_type].get("schema"), bool(request.get("required"))
    return next((value.get("schema") for value in content.values()), None), bool(
        request.get("required")
    )


def operations(document: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for path, path_item in document.get("paths", {}).items():
        inherited = path_item.get("parameters", [])
        for method in HTTP_METHODS:
            operation = path_item.get(method)
            if not operation:
                continue
            operation_id = operation.get("operationId")
            if not isinstance(operation_id, str) or not re.fullmatch(
                r"[A-Za-z][A-Za-z0-9_]*", operation_id
            ):
                fail(f"invalid or missing operationId for {method.upper()} {path}")
            if operation_id in seen:
                fail(f"duplicate operationId: {operation_id}")
            seen.add(operation_id)
            parameters: dict[tuple[str, str], dict[str, Any]] = {}
            for parameter in [*inherited, *operation.get("parameters", [])]:
                parameter = resolve_local(document, parameter)
                parameters[(parameter.get("in", ""), parameter.get("name", ""))] = parameter
            body_schema, body_required = request_schema(document, operation)
            result.append(
                {
                    "operationId": operation_id,
                    "method": method.upper(),
                    "path": path,
                    "tags": operation.get("tags", []),
                    "pathParameters": sorted(
                        name for location, name in parameters if location == "path"
                    ),
                    "queryParameters": sorted(
                        name for location, name in parameters if location == "query"
                    ),
                    "headerParameters": sorted(
                        name for location, name in parameters if location == "header"
                    ),
                    "requestSchema": schema_label(body_schema),
                    "requestRequired": body_required,
                    "responseSchema": schema_label(response_schema(document, operation)),
                }
            )
    return result


def merge_schema(
    schema: dict[str, Any], schemas: dict[str, dict[str, Any]], stack: tuple[str, ...] = ()
) -> dict[str, Any]:
    if "$ref" in schema:
        name = ref_name(schema)
        if not name or name in stack:
            return schema
        return merge_schema(schemas[name], schemas, (*stack, name))
    if "allOf" not in schema:
        return schema
    merged: dict[str, Any] = {"type": "object", "properties": {}, "required": []}
    for component in schema["allOf"]:
        resolved = merge_schema(component, schemas, stack)
        merged["properties"].update(resolved.get("properties", {}))
        merged["required"].extend(resolved.get("required", []))
    merged["required"] = sorted(set(merged["required"]))
    return merged


def py_type(schema: dict[str, Any] | None) -> str:
    if not schema:
        return "Any"
    name = ref_name(schema)
    if name:
        return name
    nullable_name = nullable_ref_name(schema)
    if nullable_name:
        return f"{nullable_name} | None"
    if schema.get("enum"):
        values = ", ".join(repr(value) for value in schema["enum"])
        return f"Literal[{values}]"
    kind = schema.get("type")
    if kind == "string":
        return "str"
    if kind == "integer":
        return "int"
    if kind == "number":
        return "float"
    if kind == "boolean":
        return "bool"
    if kind == "array":
        return f"list[{py_type(schema.get('items'))}]"
    if kind == "object":
        additional = schema.get("additionalProperties")
        return f"dict[str, {py_type(additional)}]" if isinstance(additional, dict) else "dict[str, Any]"
    return "Any"


def python_models(schemas: dict[str, dict[str, Any]]) -> str:
    lines = [
        '"""Generated OpenAPI models. Do not edit."""',
        "",
        "from __future__ import annotations",
        "",
        "from typing import Any, Literal, TypedDict",
        "",
        f'GENERATOR = "{GENERATOR}"',
        "",
    ]
    for name, raw in schemas.items():
        schema = merge_schema(raw, schemas, (name,))
        if raw.get("type") == "string" and raw.get("enum"):
            lines.append(f"{name} = {py_type(raw)}")
            lines.append("")
            continue
        properties = schema.get("properties", {})
        lines.append(f"class {name}(TypedDict, total=False):")
        if not properties:
            lines.append("    pass")
        for prop, prop_schema in properties.items():
            if not prop.isidentifier() or keyword.iskeyword(prop):
                fail(f"Python model property is not a valid identifier: {name}.{prop}")
            lines.append(f"    {prop}: {py_type(prop_schema)}")
        lines.append("")
    exported = ", ".join(repr(name) for name in schemas)
    lines.extend([f"__all__ = [{exported}]", ""])
    return "\n".join(lines)


def python_client(ops: list[dict[str, Any]]) -> str:
    descriptors = "\n".join(
        "    "
        + repr(op["operationId"])
        + ": Operation("
        + ", ".join(
            [
                repr(op["operationId"]),
                repr(op["method"]),
                repr(op["path"]),
                repr(tuple(op["pathParameters"])),
                repr(tuple(op["queryParameters"])),
                repr(tuple(op["headerParameters"])),
                repr(op["requestSchema"]),
                repr(op["requestRequired"]),
                repr(op["responseSchema"]),
            ]
        )
        + "),"
        for op in ops
    )
    methods: list[str] = []
    for op in ops:
        methods.extend(
            [
                f"    def {op['operationId']}(self, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:",
                f"        return self.call({op['operationId']!r}, path=path, query=query, body=body, headers=headers)",
                "",
            ]
        )
    return f'''"""Generated full-operation OpenAPI client. Do not edit."""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable, Mapping

GENERATOR = {GENERATOR!r}


@dataclass(frozen=True)
class Operation:
    operation_id: str
    method: str
    path: str
    path_parameters: tuple[str, ...]
    query_parameters: tuple[str, ...]
    header_parameters: tuple[str, ...]
    request_schema: str
    request_required: bool
    response_schema: str


@dataclass(frozen=True)
class ApiError(Exception):
    status: int
    code: str
    message: str
    request_id: str | None = None

    def __str__(self) -> str:
        request = f" request_id={{self.request_id}}" if self.request_id else ""
        return f"{{self.status}} {{self.code}}: {{self.message}}{{request}}"


Transport = Callable[[str, str, Mapping[str, str], bytes | None], tuple[int, Mapping[str, str], bytes]]

OPERATIONS: dict[str, Operation] = {{
{descriptors}
}}


class BrowserCloudGeneratedClient:
    def __init__(self, base_url: str, *, tenant_id: str, access_token: str | None = None, actor_id: str | None = None, timeout_seconds: float = 30.0, transport: Transport | None = None) -> None:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            raise ValueError("base_url must be an absolute HTTP(S) URL")
        if not tenant_id:
            raise ValueError("tenant_id is required")
        self._base_url = base_url.rstrip("/")
        self._tenant_id = tenant_id
        self._access_token = access_token
        self._actor_id = actor_id
        self._timeout_seconds = timeout_seconds
        self._transport = transport or self._urllib_transport

    def call(self, operation_id: str, *, path: Mapping[str, Any] | None = None, query: Mapping[str, Any] | None = None, body: Any = None, headers: Mapping[str, str] | None = None) -> Any:
        operation = OPERATIONS.get(operation_id)
        if operation is None:
            raise ValueError(f"unknown OpenAPI operation: {{operation_id}}")
        path_values = dict(path or {{}})
        route = operation.path
        for name in operation.path_parameters:
            if name not in path_values:
                raise ValueError(f"missing path parameter {{name}} for {{operation_id}}")
            route = route.replace("{{" + name + "}}", urllib.parse.quote(str(path_values[name]), safe=""))
        query_values = {{key: value for key, value in (query or {{}}).items() if value is not None}}
        unknown_query = set(query_values) - set(operation.query_parameters)
        if unknown_query:
            raise ValueError(f"unknown query parameters for {{operation_id}}: {{sorted(unknown_query)}}")
        if operation.request_required and body is None:
            raise ValueError(f"request body is required for {{operation_id}}")
        encoded_query = urllib.parse.urlencode(query_values, doseq=True)
        url = self._base_url + route + (("?" + encoded_query) if encoded_query else "")
        request_headers = {{"Accept": "application/json", "Content-Type": "application/json"}}
        controlled_headers = {{"authorization", "x-tenant-id", "x-actor-id"}}
        allowed_headers = {{name.lower() for name in operation.header_parameters}} - controlled_headers
        for name, value in (headers or {{}}).items():
            if name.lower() not in allowed_headers:
                raise ValueError(f"unknown or identity-controlled header {{name}} for {{operation_id}}")
            request_headers[name] = value
        if self._access_token:
            request_headers["Authorization"] = "Bearer " + self._access_token
        else:
            request_headers["X-Tenant-Id"] = self._tenant_id
            if self._actor_id:
                request_headers["X-Actor-Id"] = self._actor_id
        payload = json.dumps(body, separators=(",", ":")).encode() if body is not None else None
        status, response_headers, response_body = self._transport(operation.method, url, request_headers, payload)
        if status < 200 or status >= 300:
            parsed_error = json.loads(response_body.decode()) if response_body else {{}}
            raise ApiError(status, parsed_error.get("code", "UNKNOWN_ERROR"), parsed_error.get("message", f"HTTP {{status}}"), parsed_error.get("requestId"))
        if not response_body:
            return None
        content_type = next((value for key, value in response_headers.items() if key.lower() == "content-type"), "")
        if "json" in content_type or response_body[:1] in (b"{{", b"["):
            return json.loads(response_body.decode())
        return response_body

    def _urllib_transport(self, method: str, url: str, headers: Mapping[str, str], body: bytes | None) -> tuple[int, Mapping[str, str], bytes]:
        request = urllib.request.Request(url, data=body, headers=dict(headers), method=method)
        try:
            with urllib.request.urlopen(request, timeout=self._timeout_seconds) as response:
                return response.status, dict(response.headers), response.read()
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers), error.read()

{chr(10).join(methods)}

__all__ = ["ApiError", "BrowserCloudGeneratedClient", "Operation", "OPERATIONS"]
'''


def go_name(value: str) -> str:
    parts = re.findall(r"[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+", value)
    result = "".join(part[:1].upper() + part[1:] for part in parts)
    return result or "Value"


def go_type(schema: dict[str, Any] | None) -> str:
    if not schema:
        return "any"
    name = ref_name(schema)
    if name:
        return name
    nullable_name = nullable_ref_name(schema)
    if nullable_name:
        return "*" + nullable_name
    kind = schema.get("type")
    if kind == "string":
        return "string"
    if kind == "integer":
        return "int64" if schema.get("format") == "int64" else "int"
    if kind == "number":
        return "float64"
    if kind == "boolean":
        return "bool"
    if kind == "array":
        return "[]" + go_type(schema.get("items"))
    if kind == "object":
        additional = schema.get("additionalProperties")
        return "map[string]" + go_type(additional) if isinstance(additional, dict) else "map[string]any"
    return "any"


def go_models(schemas: dict[str, dict[str, Any]]) -> str:
    lines = ["// Code generated from session-api.yaml; DO NOT EDIT.", "", "package generated", ""]
    for name, raw in schemas.items():
        if raw.get("type") == "string" and raw.get("enum"):
            lines.extend([f"type {name} string", "", "const ("])
            for value in raw["enum"]:
                lines.append(f'\t{name}{go_name(str(value))} {name} = {json.dumps(value)}')
            lines.extend([")", ""])
            continue
        schema = merge_schema(raw, schemas, (name,))
        properties = schema.get("properties", {})
        lines.append(f"type {name} struct {{")
        if not properties:
            lines.append("\tAdditionalProperties map[string]any `json:\"-\"`")
        for prop, prop_schema in properties.items():
            lines.append(f"\t{go_name(prop)} {go_type(prop_schema)} `json:\"{prop},omitempty\"`")
        lines.extend(["}", ""])
    return "\n".join(lines)


def go_client(ops: list[dict[str, Any]]) -> str:
    descriptors = "\n".join(
        f'\t{json.dumps(op["operationId"])}: {{OperationID: {json.dumps(op["operationId"])}, Method: {json.dumps(op["method"])}, Path: {json.dumps(op["path"])}, PathParameters: {go_string_slice(op["pathParameters"])}, QueryParameters: {go_string_slice(op["queryParameters"])}, HeaderParameters: {go_string_slice(op["headerParameters"])}, RequestSchema: {json.dumps(op["requestSchema"])}, RequestRequired: {json.dumps(op["requestRequired"])}, ResponseSchema: {json.dumps(op["responseSchema"])} }},'
        for op in ops
    )
    methods = "\n".join(
        f'func (c *Client) {go_name(op["operationId"])}(ctx context.Context, request Request) (any, *http.Response, error) {{ return c.Call(ctx, {json.dumps(op["operationId"])}, request) }}'
        for op in ops
    )
    return f'''// Code generated from session-api.yaml; DO NOT EDIT.

package generated

import (
    "bytes"
    "context"
    "encoding/json"
    "errors"
    "fmt"
    "io"
    "net/http"
    "net/url"
    "strings"
    "time"
)

const Generator = {json.dumps(GENERATOR)}

type Operation struct {{
    OperationID string
    Method string
    Path string
    PathParameters []string
    QueryParameters []string
    HeaderParameters []string
    RequestSchema string
    RequestRequired bool
    ResponseSchema string
}}

type Request struct {{
    Path map[string]string
    Query url.Values
    Headers http.Header
    Body any
}}

type Options struct {{ BaseURL, TenantID, AccessToken, ActorID string; HTTPClient *http.Client }}
type Client struct {{ baseURL, tenantID, accessToken, actorID string; httpClient *http.Client }}
type APIError struct {{ Status int; Code, Message, RequestID string }}
func (e *APIError) Error() string {{ return fmt.Sprintf("%d %s: %s request_id=%s", e.Status, e.Code, e.Message, e.RequestID) }}

var Operations = map[string]Operation{{
{descriptors}
}}

func New(options Options) (*Client, error) {{
    parsed, err := url.Parse(options.BaseURL)
    if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {{ return nil, errors.New("base URL must be an absolute HTTP(S) URL") }}
    if options.TenantID == "" {{ return nil, errors.New("tenant ID is required") }}
    client := options.HTTPClient
    if client == nil {{ client = &http.Client{{Timeout: 30 * time.Second}} }}
    return &Client{{baseURL: strings.TrimRight(options.BaseURL, "/"), tenantID: options.TenantID, accessToken: options.AccessToken, actorID: options.ActorID, httpClient: client}}, nil
}}

func (c *Client) Call(ctx context.Context, operationID string, request Request) (any, *http.Response, error) {{
    operation, ok := Operations[operationID]
    if !ok {{ return nil, nil, fmt.Errorf("unknown OpenAPI operation: %s", operationID) }}
    route := operation.Path
    for _, name := range operation.PathParameters {{
        value, exists := request.Path[name]
        if !exists {{ return nil, nil, fmt.Errorf("missing path parameter %s for %s", name, operationID) }}
        route = strings.ReplaceAll(route, "{{"+name+"}}", url.PathEscape(value))
    }}
    allowedQuery := map[string]bool{{}}
    for _, name := range operation.QueryParameters {{ allowedQuery[name] = true }}
    for name := range request.Query {{ if !allowedQuery[name] {{ return nil, nil, fmt.Errorf("unknown query parameter %s for %s", name, operationID) }} }}
    if operation.RequestRequired && request.Body == nil {{ return nil, nil, fmt.Errorf("request body is required for %s", operationID) }}
    controlledHeaders := map[string]bool{{"authorization": true, "x-tenant-id": true, "x-actor-id": true}}
    allowedHeaders := map[string]bool{{}}
    for _, name := range operation.HeaderParameters {{ normalized := strings.ToLower(name); if !controlledHeaders[normalized] {{ allowedHeaders[normalized] = true }} }}
    for name := range request.Headers {{ if !allowedHeaders[strings.ToLower(name)] {{ return nil, nil, fmt.Errorf("unknown or identity-controlled header %s for %s", name, operationID) }} }}
    var body io.Reader
    if request.Body != nil {{ payload, err := json.Marshal(request.Body); if err != nil {{ return nil, nil, err }}; body = bytes.NewReader(payload) }}
    endpoint := c.baseURL + route
    if encoded := request.Query.Encode(); encoded != "" {{ endpoint += "?" + encoded }}
    httpRequest, err := http.NewRequestWithContext(ctx, operation.Method, endpoint, body)
    if err != nil {{ return nil, nil, err }}
    httpRequest.Header.Set("Accept", "application/json")
    httpRequest.Header.Set("Content-Type", "application/json")
    for name, values := range request.Headers {{ for _, value := range values {{ httpRequest.Header.Add(name, value) }} }}
    if c.accessToken != "" {{ httpRequest.Header.Set("Authorization", "Bearer "+c.accessToken) }} else {{ httpRequest.Header.Set("X-Tenant-Id", c.tenantID); if c.actorID != "" {{ httpRequest.Header.Set("X-Actor-Id", c.actorID) }} }}
    response, err := c.httpClient.Do(httpRequest)
    if err != nil {{ return nil, nil, err }}
    defer response.Body.Close()
    payload, err := io.ReadAll(response.Body)
    if err != nil {{ return nil, response, err }}
    if response.StatusCode < 200 || response.StatusCode >= 300 {{ var envelope struct {{ Code, Message, RequestID string }}; _ = json.Unmarshal(payload, &envelope); return nil, response, &APIError{{response.StatusCode, envelope.Code, envelope.Message, envelope.RequestID}} }}
    if len(payload) == 0 {{ return nil, response, nil }}
    var result any
    if strings.Contains(response.Header.Get("Content-Type"), "json") || payload[0] == '{{' || payload[0] == '[' {{ if err := json.Unmarshal(payload, &result); err != nil {{ return nil, response, err }}; return result, response, nil }}
    return payload, response, nil
}}

{methods}
'''


def go_string_slice(values: Iterable[str]) -> str:
    values = list(values)
    return "nil" if not values else "[]string{" + ", ".join(json.dumps(value) for value in values) + "}"


def java_name(value: str) -> str:
    name = go_name(value)
    return name[:1].upper() + name[1:]


def java_type(schema: dict[str, Any] | None) -> str:
    if not schema:
        return "Object"
    name = ref_name(schema)
    if name:
        return name
    nullable_name = nullable_ref_name(schema)
    if nullable_name:
        return nullable_name
    kind = schema.get("type")
    if kind == "string":
        return "String"
    if kind == "integer":
        return "Long" if schema.get("format") == "int64" else "Integer"
    if kind == "number":
        return "Double"
    if kind == "boolean":
        return "Boolean"
    if kind == "array":
        return f"List<{java_type(schema.get('items'))}>"
    if kind == "object":
        additional = schema.get("additionalProperties")
        return f"Map<String, {java_type(additional)}>" if isinstance(additional, dict) else "Map<String, Object>"
    return "Object"


def java_models(schemas: dict[str, dict[str, Any]]) -> str:
    lines = [
        "// Code generated from session-api.yaml; DO NOT EDIT.",
        "package io.browsercloud.sdk.generated;",
        "",
        "import java.util.List;",
        "import java.util.Map;",
        "",
        "public final class Models {",
        "  private Models() {}",
        "",
    ]
    for name, raw in schemas.items():
        if raw.get("type") == "string" and raw.get("enum"):
            values = ", ".join(java_name(str(value)) for value in raw["enum"])
            lines.extend([f"  public enum {name} {{ {values} }}", ""])
            continue
        schema = merge_schema(raw, schemas, (name,))
        properties = schema.get("properties", {})
        if not properties:
            lines.extend([f"  public record {name}(Map<String, Object> values) {{}}", ""])
            continue
        components = []
        for prop, prop_schema in properties.items():
            if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", prop):
                fail(f"Java model property is not a valid identifier: {name}.{prop}")
            components.append(f"{java_type(prop_schema)} {prop}")
        lines.extend([f"  public record {name}({', '.join(components)}) {{}}", ""])
    lines.extend(["}", ""])
    return "\n".join(lines)


def java_client(ops: list[dict[str, Any]]) -> str:
    descriptors = "\n".join(
        f'    operation({json.dumps(op["operationId"])}, {json.dumps(op["method"])}, {json.dumps(op["path"])}, {java_list(op["pathParameters"])}, {java_list(op["queryParameters"])}, {java_list(op["headerParameters"])}, {json.dumps(op["requestSchema"])}, {str(op["requestRequired"]).lower()}, {json.dumps(op["responseSchema"])}),'
        for op in ops
    ).rstrip(",")
    methods = "\n".join(
        f'  public Response {op["operationId"]}(Request request) {{ return call({json.dumps(op["operationId"])}, request); }}'
        for op in ops
    )
    return f'''// Code generated from session-api.yaml; DO NOT EDIT.
package io.browsercloud.sdk.generated;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class BrowserCloudGeneratedClient {{
  public static final String GENERATOR = {json.dumps(GENERATOR)};
  public interface Transport {{ Response send(String method, URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException; }}
  public record Response(int status, Map<String, String> headers, String body) {{}}
  public record Request(Map<String, String> path, Map<String, List<String>> query, Map<String, String> headers, String jsonBody) {{
    public Request {{ path = path == null ? Map.of() : Map.copyOf(path); query = query == null ? Map.of() : Map.copyOf(query); headers = headers == null ? Map.of() : Map.copyOf(headers); }}
    public static Request empty() {{ return new Request(Map.of(), Map.of(), Map.of(), null); }}
  }}
  public record Operation(String operationId, String method, String path, List<String> pathParameters, List<String> queryParameters, List<String> headerParameters, String requestSchema, boolean requestRequired, String responseSchema) {{}}
  public static final class ApiException extends RuntimeException {{
    private final int status; private final String code; private final String requestId;
    public ApiException(int status, String code, String message, String requestId) {{ super(message); this.status = status; this.code = code; this.requestId = requestId; }}
    public int status() {{ return status; }} public String code() {{ return code; }} public String requestId() {{ return requestId; }}
  }}
  public static final Map<String, Operation> OPERATIONS = List.of(
{descriptors}
  ).stream().collect(Collectors.toUnmodifiableMap(Operation::operationId, value -> value));

  private final URI baseUri; private final String tenantId; private final String accessToken; private final String actorId; private final Transport transport;
  public BrowserCloudGeneratedClient(String baseUrl, String tenantId, String accessToken, String actorId, Transport transport) {{
    URI parsed; try {{ parsed = URI.create(Objects.requireNonNull(baseUrl)); }} catch (RuntimeException error) {{ throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL", error); }}
    if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme())) || parsed.getHost() == null) throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL");
    if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
    this.baseUri = URI.create(baseUrl.replaceAll("/+$", "")); this.tenantId = tenantId; this.accessToken = accessToken; this.actorId = actorId; this.transport = transport == null ? httpTransport() : transport;
  }}
  public Response call(String operationId, Request request) {{
    var operation = OPERATIONS.get(operationId); if (operation == null) throw new IllegalArgumentException("unknown OpenAPI operation: " + operationId);
    Objects.requireNonNull(request, "request");
    var route = operation.path();
    for (var name : operation.pathParameters()) {{ var value = request.path().get(name); if (value == null) throw new IllegalArgumentException("missing path parameter " + name + " for " + operationId); route = route.replace("{{" + name + "}}", URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")); }}
    for (var name : request.query().keySet()) if (!operation.queryParameters().contains(name)) throw new IllegalArgumentException("unknown query parameter " + name + " for " + operationId);
    if (operation.requestRequired() && request.jsonBody() == null) throw new IllegalArgumentException("request body is required for " + operationId);
    var controlledHeaders = List.of("authorization", "x-tenant-id", "x-actor-id");
    var allowedHeaders = operation.headerParameters().stream().map(String::toLowerCase).filter(name -> !controlledHeaders.contains(name)).collect(Collectors.toSet());
    for (var name : request.headers().keySet()) if (!allowedHeaders.contains(name.toLowerCase())) throw new IllegalArgumentException("unknown or identity-controlled header " + name + " for " + operationId);
    var query = request.query().entrySet().stream().flatMap(entry -> entry.getValue().stream().map(value -> encode(entry.getKey()) + "=" + encode(value))).collect(Collectors.joining("&"));
    var uri = URI.create(baseUri + route + (query.isEmpty() ? "" : "?" + query));
    var headers = new LinkedHashMap<String, String>(); headers.put("Accept", "application/json"); headers.put("Content-Type", "application/json");
    headers.putAll(request.headers());
    if (accessToken != null && !accessToken.isBlank()) headers.put("Authorization", "Bearer " + accessToken); else {{ headers.put("X-Tenant-Id", tenantId); if (actorId != null && !actorId.isBlank()) headers.put("X-Actor-Id", actorId); }}
    try {{ var response = transport.send(operation.method(), uri, headers, request.jsonBody()); if (response.status() < 200 || response.status() >= 300) throw new ApiException(response.status(), jsonString(response.body(), "code", "UNKNOWN_ERROR"), jsonString(response.body(), "message", "HTTP " + response.status()), jsonString(response.body(), "requestId", null)); return response; }}
    catch (IOException error) {{ throw new IllegalStateException("Browser Cloud request failed", error); }} catch (InterruptedException error) {{ Thread.currentThread().interrupt(); throw new IllegalStateException("Browser Cloud request interrupted", error); }}
  }}
  private static Operation operation(String id, String method, String path, List<String> pathParameters, List<String> queryParameters, List<String> headerParameters, String requestSchema, boolean requestRequired, String responseSchema) {{ return new Operation(id, method, path, pathParameters, queryParameters, headerParameters, requestSchema, requestRequired, responseSchema); }}
  private static String encode(String value) {{ return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }}
  private static Transport httpTransport() {{ var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(); return (method, uri, headers, body) -> {{ var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)); headers.forEach(builder::header); var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString()); return new Response(response.statusCode(), response.headers().map().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> String.join(",", entry.getValue()))), response.body()); }}; }}
  private static String jsonString(String json, String key, String fallback) {{ var matcher = java.util.regex.Pattern.compile("\\\"" + java.util.regex.Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(json == null ? "" : json); return matcher.find() ? matcher.group(1).replace("\\\\\\\"", "\\\"").replace("\\\\\\\\", "\\\\") : fallback; }}

{methods}
}}
'''


def java_list(values: Iterable[str]) -> str:
    values = list(values)
    return "List.of()" if not values else "List.of(" + ", ".join(json.dumps(value) for value in values) + ")"


def write(path: pathlib.Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 4:
        fail("usage: generate_multilang_sdks.py OPENAPI_JSON OPENAPI_YAML REPOSITORY_ROOT")
    bundled = pathlib.Path(sys.argv[1]).resolve()
    contract = pathlib.Path(sys.argv[2]).resolve()
    root = pathlib.Path(sys.argv[3]).resolve()
    document = json.loads(bundled.read_text(encoding="utf-8"))
    if document.get("openapi") != "3.1.0":
        fail("only the reviewed OpenAPI 3.1.0 contract is supported")
    ops = operations(document)
    schemas = document.get("components", {}).get("schemas", {})
    if not ops or not schemas:
        fail("OpenAPI contract must contain operations and component schemas")
    targets = {
        "python/browsercloud/generated_client.py": python_client(ops),
        "python/browsercloud/generated_models.py": python_models(schemas),
        "go/browsercloud/generated/client.gen.go": go_client(ops),
        "go/browsercloud/generated/models.gen.go": go_models(schemas),
        "java/src/main/java/io/browsercloud/sdk/generated/BrowserCloudGeneratedClient.java": java_client(ops),
        "java/src/main/java/io/browsercloud/sdk/generated/Models.java": java_models(schemas),
    }
    sdk_root = root / "sdks"
    for relative, content in targets.items():
        write(sdk_root / relative, content)
    subprocess.run(
        [
            "gofmt",
            "-w",
            str(sdk_root / "go/browsercloud/generated/client.gen.go"),
            str(sdk_root / "go/browsercloud/generated/models.gen.go"),
        ],
        check=True,
    )
    manifest = {
        "formatVersion": 1,
        "generator": GENERATOR,
        "contract": contract.name,
        "contractSha256": sha256(contract),
        "operationCount": len(ops),
        "schemaCount": len(schemas),
        "files": {
            relative: sha256(sdk_root / relative) for relative in sorted(targets)
        },
    }
    write(
        sdk_root / "generated-multilang-manifest.json",
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
    )
    print(f"multilang_sdk_generated=true operations={len(ops)} schemas={len(schemas)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
