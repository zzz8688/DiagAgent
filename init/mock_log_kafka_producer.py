#!/usr/bin/env python3
"""
模拟日志 Kafka 生产者。

职责：
1. 生成标准格式的模拟日志事件；
2. 将同一批日志事件直接发送到 Kafka；
3. 先发一批较多的初始化日志，再以较低速率持续产生日志流。
"""

import argparse
import json
import random
import time
import uuid
from datetime import datetime, timedelta, timezone


SERVICES = [
    "frontend",
    "order-service",
    "payment-service",
    "product-service",
    "gateway-service",
    "auth-service",
    "recommendation-service",
    "promotion-service",
]
PAYMENT_FLOW_SERVICES = {"frontend", "order-service", "payment-service", "gateway-service"}

ERROR_MESSAGES = {
    "frontend": [
        "Checkout submit failed - upstream /api/orders/checkout returned 504",
        "Session refresh loop detected - user stayed on loading screen for 12s",
        "Product page first screen blocked - hydration timeout exceeded threshold",
    ],
    "order-service": [
        "Checkout failed - payment-service returned 504 Gateway Timeout",
        "Intermittent downstream dependency error - target fluctuated between payment-service and promotion-service",
        "Order submit failed - auth context missing after token refresh timeout",
    ],
    "payment-service": [
        "Payment gateway 503 Service Unavailable",
        "Payment gateway timeout\njava.net.SocketTimeoutException: read timed out\n\tat com.demo.PaymentClient.call(PaymentClient.java:42)\nCaused by: upstream closed connection",
        "Payment retry exhausted - waiting for gateway receipt timed out",
    ],
    "product-service": [
        "Product cache miss storm - fallback aggregation exceeded 1800ms",
        "Product detail request blocked by recommendation-service timeout",
        "Connection timeout while borrowing JDBC connection from inventory-db",
    ],
    "gateway-service": [
        "Gateway routing degraded - upstream error rate exceeded threshold",
        "Gateway health check failed - 2 instances marked unhealthy",
    ],
    "auth-service": [
        "Token validation timeout - auth-service could not refresh session in time",
        "Session refresh failed - repeated 401 from auth-provider",
    ],
    "recommendation-service": [
        "Recommendation request timeout - profile-service enrichment exceeded 1600ms",
        "Recommendation dependency degraded - fallback list generation failed",
    ],
    "promotion-service": [
        "Promotion rule engine timeout - checkout discount pipeline stalled",
        "Promotion dependency jitter detected during order submit",
    ],
}

WARN_MESSAGES = {
    "frontend": [
        "Checkout page polling detected - waiting for payment confirmation",
        "First screen resource download exceeded 1500ms on /products",
        "Frontend redirected to login twice within one request window",
    ],
    "order-service": [
        "Retry attempt for payment-service call",
        "Downstream success rate dropped but failure point is not stable",
        "Order lock wait time above threshold",
    ],
    "payment-service": [
        "Payment channel fallback activated",
        "Circuit breaker half-open for payment-gateway",
        "Payment gateway receipt delayed - pending queue growing",
    ],
    "product-service": [
        "Product detail cache hit ratio dropped below 45%",
        "Product page dependency latency above threshold",
        "Inventory-db waiters above threshold",
    ],
    "gateway-service": [
        "Gateway error rate above threshold: 3.2%",
        "Gateway instance health score dropped below 70",
    ],
    "auth-service": [
        "Token validation latency p99 above 1400ms",
        "Session refresh backlog above threshold",
    ],
    "recommendation-service": [
        "Recommendation latency above threshold: 1800ms",
        "Profile enrichment fallback activated",
    ],
    "promotion-service": [
        "Promotion dependency retry triggered",
        "Promotion response time above threshold",
    ],
}

INFO_MESSAGES = {
    "frontend": [
        "Received request from user_{user_id} for /checkout",
        "Rendered checkout page for user_{user_id}",
        "Checkout status poll completed for user_{user_id}",
        "Rendered product page shell for user_{user_id}",
    ],
    "order-service": [
        "Order request accepted for user_{user_id}",
        "Order state persisted for user_{user_id}",
        "Order workflow heartbeat for user_{user_id}",
    ],
    "payment-service": [
        "Payment authorization requested for user_{user_id}",
        "Payment callback received for user_{user_id}",
        "Payment reconciliation heartbeat for user_{user_id}",
    ],
    "product-service": [
        "Product detail cache refreshed for sku_{sku_id}",
        "Product aggregation request started for user_{user_id}",
        "Product read model sync completed",
    ],
    "gateway-service": [
        "Gateway route check completed for /api/payments",
        "Gateway health probe passed for payment-service",
        "Gateway traffic split recalculated",
    ],
    "auth-service": [
        "Token validation completed for user_{user_id}",
        "Session refresh completed for user_{user_id}",
        "Auth cache heartbeat for tenant default",
    ],
    "recommendation-service": [
        "Recommendation request accepted for user_{user_id}",
        "Profile enrichment completed for user_{user_id}",
        "Recommendation cache warmed for sku_{sku_id}",
    ],
    "promotion-service": [
        "Promotion rules refreshed for campaign_{sku_id}",
        "Promotion eligibility check completed for user_{user_id}",
        "Promotion engine heartbeat for shard-a",
    ],
}

SPECIAL_PAYMENT_TRACE_ID = "a1b2c3d4e5f6g7h8"
SPECIAL_PAYMENT_REQUEST_ID = "req-pay-critical-001"
SPECIAL_PRODUCT_TRACE_ID = "trace-product-1044"
SPECIAL_PRODUCT_REQUEST_ID = "req-product-slow-001"
SPECIAL_DB_TRACE_ID = "trace-db-4201"
SPECIAL_DB_REQUEST_ID = "req-db-critical-001"
SPECIAL_HEALTH_TRACE_ID = "trace-health-0001"
SPECIAL_HEALTH_REQUEST_ID = "req-health-001"
SPECIAL_AMBIGUOUS_TRACE_ID = "trace-amb-9910"
SPECIAL_AMBIGUOUS_REQUEST_ID = "req-amb-001"

SPECIAL_SEED_EVENTS = [
    {
        "service": "frontend",
        "level": "WARN",
        "trace_id": SPECIAL_PAYMENT_TRACE_ID,
        "request_id": SPECIAL_PAYMENT_REQUEST_ID,
        "message": "Checkout submit failed, upstream /api/orders/checkout returned 504, traceId=a1b2c3d4e5f6g7h8",
    },
    {
        "service": "order-service",
        "level": "WARN",
        "trace_id": SPECIAL_PAYMENT_TRACE_ID,
        "request_id": SPECIAL_PAYMENT_REQUEST_ID,
        "message": "Checkout workflow blocked because payment-service returned 504 Gateway Timeout, traceId=a1b2c3d4e5f6g7h8",
    },
    {
        "service": "payment-service",
        "level": "ERROR",
        "trace_id": SPECIAL_PAYMENT_TRACE_ID,
        "request_id": SPECIAL_PAYMENT_REQUEST_ID,
        "message": "Payment gateway timeout after 3200ms while calling payment-gateway, traceId=a1b2c3d4e5f6g7h8, upstreamStatus=504",
    },
    {
        "service": "payment-service",
        "level": "WARN",
        "trace_id": SPECIAL_PAYMENT_TRACE_ID,
        "request_id": SPECIAL_PAYMENT_REQUEST_ID,
        "message": "Retry policy exhausted for payment-gateway, attempts=3, backoffMs=200/400/800, traceId=a1b2c3d4e5f6g7h8",
    },
    {
        "service": "payment-service",
        "level": "WARN",
        "trace_id": SPECIAL_PAYMENT_TRACE_ID,
        "request_id": SPECIAL_PAYMENT_REQUEST_ID,
        "message": "Circuit breaker half-open for payment-gateway, recentFailureRate=71%, traceId=a1b2c3d4e5f6g7h8",
    },
    {
        "service": "frontend",
        "level": "WARN",
        "trace_id": SPECIAL_PAYMENT_TRACE_ID,
        "request_id": SPECIAL_PAYMENT_REQUEST_ID,
        "message": "Payment result polling still pending after 12s because upstream gateway receipt is missing, requestId=req-pay-critical-001",
    },
    {
        "service": "payment-service",
        "level": "INFO",
        "trace_id": SPECIAL_PAYMENT_TRACE_ID,
        "request_id": SPECIAL_PAYMENT_REQUEST_ID,
        "message": "Deployment event: payment-service release checkout-hotfix-20260622 version=2.3.7 environment=mock",
    },
    {
        "service": "payment-service",
        "level": "INFO",
        "trace_id": SPECIAL_PAYMENT_TRACE_ID,
        "request_id": SPECIAL_PAYMENT_REQUEST_ID,
        "message": "Configuration change applied: PAYMENT_GATEWAY_TIMEOUT_MS 1500->3000, featureFlag=payment_gateway_retry_enabled, service=payment-service",
    },
    {
        "service": "frontend",
        "level": "WARN",
        "trace_id": SPECIAL_PRODUCT_TRACE_ID,
        "request_id": SPECIAL_PRODUCT_REQUEST_ID,
        "message": "Product page first screen blocked by main bundle download 1.8MB and hydration cost 1460ms, requestId=req-product-slow-001",
    },
    {
        "service": "product-service",
        "level": "INFO",
        "trace_id": SPECIAL_PRODUCT_TRACE_ID,
        "request_id": SPECIAL_PRODUCT_REQUEST_ID,
        "message": "Cache miss ratio spiked for product detail cache, hitRate=41%, fallback to downstream aggregation, traceId=trace-product-1044",
    },
    {
        "service": "recommendation-service",
        "level": "WARN",
        "trace_id": SPECIAL_PRODUCT_TRACE_ID,
        "request_id": SPECIAL_PRODUCT_REQUEST_ID,
        "message": "Recommendation request latency reached 1820ms, waiting for profile-service enrichment, traceId=trace-product-1044",
    },
    {
        "service": "frontend",
        "level": "INFO",
        "trace_id": SPECIAL_PRODUCT_TRACE_ID,
        "request_id": SPECIAL_PRODUCT_REQUEST_ID,
        "message": "CDN cache miss for product-page.js, origin fetch cost 920ms, requestId=req-product-slow-001",
    },
    {
        "service": "product-service",
        "level": "ERROR",
        "trace_id": SPECIAL_DB_TRACE_ID,
        "request_id": SPECIAL_DB_REQUEST_ID,
        "message": "Connection timeout while borrowing JDBC connection from pool primary, waited 3000ms, traceId=trace-db-4201",
    },
    {
        "service": "product-service",
        "level": "WARN",
        "trace_id": SPECIAL_DB_TRACE_ID,
        "request_id": SPECIAL_DB_REQUEST_ID,
        "message": "Slow query detected on inventory_db.products, cost=1840ms, waitingBorrowCount=11, traceId=trace-db-4201",
    },
    {
        "service": "order-service",
        "level": "ERROR",
        "trace_id": SPECIAL_DB_TRACE_ID,
        "request_id": SPECIAL_DB_REQUEST_ID,
        "message": "Downstream product-service timeout after 3200ms, root sample points to inventory-db connection pool exhaustion, traceId=trace-db-4201",
    },
    {
        "service": "gateway-service",
        "level": "WARN",
        "trace_id": SPECIAL_HEALTH_TRACE_ID,
        "request_id": SPECIAL_HEALTH_REQUEST_ID,
        "message": "Gateway service health score dropped to 61, unhealthyInstances=2/8, traceId=trace-health-0001",
    },
    {
        "service": "auth-service",
        "level": "WARN",
        "trace_id": SPECIAL_HEALTH_TRACE_ID,
        "request_id": SPECIAL_HEALTH_REQUEST_ID,
        "message": "Token validation latency p99 exceeded 1400ms and login refresh queue backlog increased, traceId=trace-health-0001",
    },
    {
        "service": "order-service",
        "level": "ERROR",
        "trace_id": SPECIAL_HEALTH_TRACE_ID,
        "request_id": SPECIAL_HEALTH_REQUEST_ID,
        "message": "Order service dependency success rate dropped to 93%, failures distributed across payment-service and promotion-service, traceId=trace-health-0001",
    },
    {
        "service": "order-service",
        "level": "ERROR",
        "trace_id": SPECIAL_AMBIGUOUS_TRACE_ID,
        "request_id": SPECIAL_AMBIGUOUS_REQUEST_ID,
        "message": "Intermittent order submit failure, upstream returned 502, downstream target fluctuated between payment-service and promotion-service, traceId=trace-amb-9910",
    },
    {
        "service": "payment-service",
        "level": "WARN",
        "trace_id": "",
        "request_id": SPECIAL_AMBIGUOUS_REQUEST_ID,
        "message": "Timeout while waiting for gateway response, traceId missing, retry succeeded on second attempt",
    },
    {
        "service": "promotion-service",
        "level": "WARN",
        "trace_id": SPECIAL_AMBIGUOUS_TRACE_ID,
        "request_id": SPECIAL_AMBIGUOUS_REQUEST_ID,
        "message": "Promotion dependency timeout observed during intermittent failure window, but not every failed order hits this path, traceId=trace-amb-9910",
    },
]


def build_parser():
    parser = argparse.ArgumentParser(
        description="生成模拟日志并作为 Kafka 生产者持续发送到 log-ingest。"
    )
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--topic", default="log-ingest")
    parser.add_argument("--source-type", default="mock-log-kafka-producer")
    parser.add_argument("--host", default="diag-agent-mock-log-producer")
    parser.add_argument("--seed-lines-per-service", type=int, default=200)
    parser.add_argument("--steady-batch-size", type=int, default=2)
    parser.add_argument("--steady-interval-seconds", type=float, default=5.0)
    parser.add_argument(
        "--steady-rounds",
        type=int,
        default=0,
        help="持续生产轮数。0 表示一直持续，直到手动停止。",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="打印每条发送的事件。",
    )
    return parser


def load_producer(bootstrap_servers):
    try:
        from kafka import KafkaProducer
        from kafka.errors import NoBrokersAvailable
    except ImportError as exc:
        raise SystemExit(
            "缺少依赖 kafka-python，请先执行: pip install kafka-python"
        ) from exc

    try:
        return KafkaProducer(
            bootstrap_servers=normalize_bootstrap_servers(bootstrap_servers),
            acks="all",
            value_serializer=lambda value: json.dumps(value).encode("utf-8"),
        )
    except NoBrokersAvailable as exc:
        raise SystemExit(
            "Kafka broker 不可用，无法连接到 "
            f"{bootstrap_servers}。\n"
            "请先确认 Docker Desktop 已启动，并执行:\n"
            "  docker-compose up -d zookeeper kafka\n"
            "如果 Kafka 已启动，再检查 9092 端口是否可访问。"
        ) from exc


def normalize_bootstrap_servers(bootstrap_servers):
    if bootstrap_servers is None:
        return bootstrap_servers
    return bootstrap_servers.replace("localhost:", "127.0.0.1:")


def build_summary(level, source_type, service, message):
    first_line = message.splitlines()[0] if message else ""
    if len(first_line) > 160:
        first_line = first_line[:160] + "..."
    return f"[{level}] {source_type}/{service} {first_line}"


def build_kafka_payload(service, trace_id, request_id, level, message, timestamp, source_type, host, source_file, line_number):
    message_id = uuid.uuid4().hex
    return {
        "messageId": message_id,
        "timestamp": timestamp.astimezone(timezone.utc).isoformat(),
        "sourceType": source_type,
        "sourceId": service,
        "host": host,
        "level": level,
        "sessionId": "",
        "taskId": "",
        "traceId": trace_id,
        "summary": build_summary(level, source_type, service, message),
        "content": message,
        "attributes": {
            "service": service,
            "requestId": request_id,
            "upstreamDependency": upstream_dependency_for(service),
            "sourceFile": source_file,
            "lineNumber": str(line_number),
        },
    }


def sample_positions(total_lines, count, start_offset=10):
    if count <= 0 or total_lines <= 0:
        return []
    upper_bound = max(total_lines - start_offset, count)
    population = list(range(min(start_offset, total_lines), upper_bound))
    if len(population) < count:
        population = list(range(total_lines))
    return sorted(random.sample(population, min(count, len(population))))


def create_service_states():
    payment_context = create_flow_context()
    catalog_context = create_flow_context()
    states = {}
    for service in SERVICES:
        states[service] = {
            "user_id": random.randint(1, 1000),
            "sku_id": random.randint(10000, 99999),
            "context": payment_context if service in PAYMENT_FLOW_SERVICES else catalog_context,
            "initial_error_positions": sample_positions(200, len(ERROR_MESSAGES[service])),
            "initial_warn_positions": sample_positions(200, len(WARN_MESSAGES[service])),
            "initial_error_index": 0,
            "initial_warn_index": 0,
        }
    return states


def reset_initial_positions(states, seed_lines_per_service):
    for service in SERVICES:
        state = states[service]
        state["initial_error_positions"] = sample_positions(seed_lines_per_service, len(ERROR_MESSAGES[service]))
        state["initial_warn_positions"] = sample_positions(seed_lines_per_service, len(WARN_MESSAGES[service]))
        state["initial_error_index"] = 0
        state["initial_warn_index"] = 0


def render_info_message(service, state):
    template = random.choice(INFO_MESSAGES[service])
    return template.format(user_id=state["user_id"], sku_id=state["sku_id"])


def build_seed_event(service, index, timestamp, state):
    if (
        state["initial_error_index"] < len(state["initial_error_positions"])
        and index == state["initial_error_positions"][state["initial_error_index"]]
    ):
        message = ERROR_MESSAGES[service][state["initial_error_index"]]
        state["initial_error_index"] += 1
        return "ERROR", message
    if (
        state["initial_warn_index"] < len(state["initial_warn_positions"])
        and index == state["initial_warn_positions"][state["initial_warn_index"]]
    ):
        message = WARN_MESSAGES[service][state["initial_warn_index"]]
        state["initial_warn_index"] += 1
        return "WARN", message
    return "INFO", render_info_message(service, state)


def build_live_event(service, state):
    roll = random.random()
    if roll < 0.06:
        return "ERROR", random.choice(ERROR_MESSAGES[service])
    if roll < 0.18:
        return "WARN", random.choice(WARN_MESSAGES[service])
    state["user_id"] = random.randint(1, 1000)
    state["sku_id"] = random.randint(10000, 99999)
    if random.random() < 0.35:
        state["context"].update(create_flow_context())
    return "INFO", render_info_message(service, state)


def create_flow_context():
    return {
        "trace_id": uuid.uuid4().hex[:16],
        "request_id": f"req-{uuid.uuid4().hex[:12]}",
    }


def upstream_dependency_for(service):
    if service == "frontend":
        return "product-service"
    if service == "order-service":
        return "payment-service"
    if service == "payment-service":
        return "payment-gateway"
    if service == "product-service":
        return "recommendation-service"
    if service == "gateway-service":
        return "external-payment-gateway"
    if service == "auth-service":
        return "auth-provider"
    if service == "recommendation-service":
        return "profile-service"
    if service == "promotion-service":
        return "rule-engine"
    return "unknown"


def emit_event(producer, topic, payload, verbose):
    producer.send(topic, payload)
    if verbose:
        print(json.dumps(payload, ensure_ascii=True), flush=True)


def generate_seed_batch(args, producer, states, line_counters):
    reset_initial_positions(states, args.seed_lines_per_service)
    base_time = datetime.now(timezone.utc) - timedelta(minutes=5)
    published = 0
    for index in range(max(args.seed_lines_per_service, 0)):
        for service in SERVICES:
            state = states[service]
            timestamp = base_time + timedelta(milliseconds=index * 100 + random.randint(0, 50))
            level, message = build_seed_event(service, index, timestamp, state)
            source_file, line_number = reserve_virtual_line_number(line_counters, service, message)
            payload = build_kafka_payload(
                service,
                state["context"]["trace_id"],
                state["context"]["request_id"],
                level,
                message,
                timestamp,
                args.source_type,
                args.host,
                source_file,
                line_number,
            )
            emit_event(producer, args.topic, payload, args.verbose)
            published += 1
    published += emit_special_seed_events(args, producer, line_counters)
    producer.flush()
    return published


def generate_live_batch(args, producer, states, line_counters):
    published = 0
    batch_size = max(args.steady_batch_size, 0)
    for _ in range(batch_size):
        service = random.choice(SERVICES)
        state = states[service]
        timestamp = datetime.now(timezone.utc)
        level, message = build_live_event(service, state)
        source_file, line_number = reserve_virtual_line_number(line_counters, service, message)
        payload = build_kafka_payload(
            service,
            state["context"]["trace_id"],
            state["context"]["request_id"],
            level,
            message,
            timestamp,
            args.source_type,
            args.host,
            source_file,
            line_number,
        )
        emit_event(producer, args.topic, payload, args.verbose)
        published += 1
    producer.flush()
    return published


def reserve_virtual_line_number(line_counters, service, message):
    current = line_counters.get(service, 0)
    start_line = current + 1
    line_counters[service] = current + max(1, len(message.splitlines()) or 1)
    return f"{service}.log", start_line


def emit_special_seed_events(args, producer, line_counters):
    published = 0
    base_time = datetime.now(timezone.utc) - timedelta(minutes=2)
    for index, event in enumerate(SPECIAL_SEED_EVENTS):
        timestamp = base_time + timedelta(seconds=index * 5)
        source_file, line_number = reserve_virtual_line_number(
            line_counters, event["service"], event["message"]
        )
        payload = build_kafka_payload(
            event["service"],
            event["trace_id"],
            event["request_id"],
            event["level"],
            event["message"],
            timestamp,
            args.source_type,
            args.host,
            source_file,
            line_number,
        )
        emit_event(producer, args.topic, payload, args.verbose)
        published += 1
    return published


def main():
    args = build_parser().parse_args()

    producer = load_producer(args.bootstrap_servers)
    states = create_service_states()
    line_counters = {}

    print(
        "启动模拟日志 Kafka 生产者: "
        f"bootstrap_servers={args.bootstrap_servers}, topic={args.topic}, "
        f"seed_lines_per_service={args.seed_lines_per_service}, "
        f"steady_batch_size={args.steady_batch_size}, "
        f"steady_interval_seconds={args.steady_interval_seconds}",
        flush=True,
    )

    seed_published = generate_seed_batch(args, producer, states, line_counters)
    print(f"初始化日志发送完成: published={seed_published}", flush=True)

    if args.steady_batch_size <= 0:
        print("steady_batch_size <= 0，跳过持续生产阶段。", flush=True)
        return

    round_index = 0
    try:
        while args.steady_rounds <= 0 or round_index < args.steady_rounds:
            time.sleep(max(args.steady_interval_seconds, 0))
            round_index += 1
            published = generate_live_batch(args, producer, states, line_counters)
            print(
                f"实时日志批次发送完成: round={round_index}, published={published}",
                flush=True,
            )
    except KeyboardInterrupt:
        print("收到停止信号，模拟日志 Kafka 生产者退出。", flush=True)
    finally:
        producer.flush()
        producer.close()


if __name__ == "__main__":
    main()
