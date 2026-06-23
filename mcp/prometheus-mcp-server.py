import argparse
import json
import sys
import urllib.parse
import urllib.request


SERVER_INFO = {
    "name": "prometheus-mcp-server",
    "version": "0.1.0",
}


def read_message():
    headers = {}
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None
        if line in (b"\r\n", b"\n"):
            break
        key, value = line.decode("utf-8").split(":", 1)
        headers[key.strip().lower()] = value.strip()

    length = int(headers.get("content-length", "0"))
    if length <= 0:
        return None
    payload = sys.stdin.buffer.read(length)
    if not payload:
        return None
    return json.loads(payload.decode("utf-8"))


def write_message(message):
    body = json.dumps(message, ensure_ascii=True).encode("utf-8")
    sys.stdout.buffer.write(f"Content-Length: {len(body)}\r\n\r\n".encode("ascii"))
    sys.stdout.buffer.write(body)
    sys.stdout.buffer.flush()


def write_result(request_id, result):
    write_message({
        "jsonrpc": "2.0",
        "id": request_id,
        "result": result,
    })


def write_error(request_id, code, message):
    write_message({
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {
            "code": code,
            "message": message,
        },
    })


def list_tools():
    return {
        "tools": [
            {
                "name": "queryPrometheusMetrics",
                "description": "Query Prometheus instant or range metrics for DiagAgent MCP experiments.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "PromQL query expression",
                        },
                        "start": {
                            "type": "string",
                            "description": "Range query start time in RFC3339 or unix timestamp",
                        },
                        "end": {
                            "type": "string",
                            "description": "Range query end time in RFC3339 or unix timestamp",
                        },
                        "step": {
                            "type": "string",
                            "description": "Range query step, for example 30s or 1m",
                        },
                    },
                    "required": ["query"],
                },
            }
        ]
    }


def call_prometheus(base_url, arguments):
    query = arguments.get("query")
    if not query:
        raise ValueError("query is required")

    if arguments.get("start") and arguments.get("end"):
        endpoint = "/api/v1/query_range"
        params = {
            "query": query,
            "start": arguments["start"],
            "end": arguments["end"],
            "step": arguments.get("step", "30s"),
        }
    else:
        endpoint = "/api/v1/query"
        params = {"query": query}

    url = base_url.rstrip("/") + endpoint + "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=10) as response:
        data = json.loads(response.read().decode("utf-8"))

    status = data.get("status", "unknown")
    result_type = data.get("data", {}).get("resultType", "unknown")
    result = data.get("data", {}).get("result", [])
    summary = {
        "status": status,
        "resultType": result_type,
        "resultCount": len(result),
        "result": result[:5],
    }
    return {
        "content": [
            {
                "type": "text",
                "text": json.dumps(summary, ensure_ascii=True, indent=2),
            }
        ]
    }


def handle_request(base_url, request):
    request_id = request.get("id")
    method = request.get("method")

    if method == "initialize":
        write_result(request_id, {
            "protocolVersion": "2024-11-05",
            "capabilities": {
                "tools": {},
            },
            "serverInfo": SERVER_INFO,
        })
        return

    if method == "notifications/initialized":
        return

    if method == "tools/list":
        write_result(request_id, list_tools())
        return

    if method == "tools/call":
        params = request.get("params", {})
        tool_name = params.get("name")
        if tool_name != "queryPrometheusMetrics":
            write_error(request_id, -32601, f"Unknown tool: {tool_name}")
            return
        try:
            result = call_prometheus(base_url, params.get("arguments", {}))
            write_result(request_id, result)
        except Exception as exc:
            write_error(request_id, -32000, str(exc))
        return

    write_error(request_id, -32601, f"Unsupported method: {method}")


def main():
    parser = argparse.ArgumentParser(description="Minimal Prometheus MCP server for DiagAgent experiments.")
    parser.add_argument("--prometheus-url", default="http://localhost:9090", help="Base URL of Prometheus API")
    args = parser.parse_args()

    while True:
        request = read_message()
        if request is None:
            break
        handle_request(args.prometheus_url, request)


if __name__ == "__main__":
    main()
