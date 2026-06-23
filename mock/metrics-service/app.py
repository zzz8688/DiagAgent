import math
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


HOST = "0.0.0.0"
PORT = 8001
STARTED_AT = time.time()


def render_metrics() -> str:
    now = time.time()
    elapsed = max(1.0, now - STARTED_AT)

    checkout_latency = 0.35 + (math.sin(elapsed / 18.0) + 1.0) * 0.12
    checkout_error_rate = 0.012 + (math.cos(elapsed / 24.0) + 1.0) * 0.008
    payment_latency = 0.48 + (math.sin(elapsed / 14.0) + 1.0) * 0.16
    payment_error_rate = 0.018 + (math.cos(elapsed / 26.0) + 1.0) * 0.012
    gateway_latency = 1.25 + (math.sin(elapsed / 11.0) + 1.0) * 0.72
    gateway_error_rate = 0.035 + (math.cos(elapsed / 17.0) + 1.0) * 0.02
    gateway_health = max(0.12, min(0.96, 0.42 + math.sin(elapsed / 13.0) * 0.22))
    payment_dependency_health = max(0.10, min(0.98, gateway_health + 0.08))
    order_dependency_health = max(0.15, min(0.99, payment_dependency_health + 0.12))
    payment_retry_exhausted = 96 + int((math.sin(elapsed / 10.0) + 1.0) * 24)
    payment_pending_requests = 240 + int((math.cos(elapsed / 12.0) + 1.0) * 60)
    circuit_breaker_open_ratio = max(0.05, min(0.92, 0.51 + math.sin(elapsed / 15.0) * 0.18))
    frontend_first_screen = 2.2 + (math.sin(elapsed / 19.0) + 1.0) * 0.72
    static_bundle_download = 0.9 + (math.cos(elapsed / 16.0) + 1.0) * 0.44
    product_cache_hit_ratio = max(0.20, min(0.96, 0.54 + math.sin(elapsed / 13.0) * 0.18))
    recommendation_latency = 1.0 + (math.sin(elapsed / 15.0) + 1.0) * 0.52
    gateway_service_health_score = 61 + (math.sin(elapsed / 21.0) + 1.0) * 6
    auth_token_validation_latency = 0.9 + (math.cos(elapsed / 18.0) + 1.0) * 0.32
    session_refresh_failure_ratio = 0.02 + (math.sin(elapsed / 20.0) + 1.0) * 0.012
    product_db_pool_active = 25 + int((math.cos(elapsed / 9.0) + 1.0) * 3)
    product_db_pool_waiters = 9 + int((math.sin(elapsed / 7.0) + 1.0) * 4)
    product_db_slow_query = 1.4 + (math.sin(elapsed / 16.0) + 1.0) * 0.55
    cpu_usage = 0.25 + (math.sin(elapsed / 10.0) + 1.0) * 0.2
    jvm_memory = 256 * 1024 * 1024 + int((math.sin(elapsed / 22.0) + 1.0) * 64 * 1024 * 1024)
    db_pool_active = 6 + int((math.cos(elapsed / 12.0) + 1.0) * 4)

    return f"""# HELP http_request_duration_seconds Mock request latency in seconds.
# TYPE http_request_duration_seconds gauge
http_request_duration_seconds{{service="mock-app-service",endpoint="/api/orders"}} {checkout_latency:.6f}
http_request_duration_seconds{{service="order-service",endpoint="/api/orders/checkout"}} {checkout_latency:.6f}
http_request_duration_seconds{{service="payment-service",endpoint="/api/payments"}} {payment_latency:.6f}
http_request_duration_seconds{{service="product-service",endpoint="/api/products"}} {frontend_first_screen:.6f}
# HELP service_error_rate Mock service error rate.
# TYPE service_error_rate gauge
service_error_rate{{service="mock-app-service"}} {checkout_error_rate:.6f}
service_error_rate{{service="order-service"}} {checkout_error_rate:.6f}
service_error_rate{{service="payment-service"}} {payment_error_rate:.6f}
service_error_rate{{service="gateway-service"}} {gateway_error_rate:.6f}
# HELP upstream_request_duration_seconds Mock upstream dependency latency.
# TYPE upstream_request_duration_seconds gauge
upstream_request_duration_seconds{{service="payment-service",dependency="payment-gateway"}} {gateway_latency:.6f}
upstream_request_duration_seconds{{service="product-service",dependency="recommendation-service"}} {recommendation_latency:.6f}
# HELP upstream_request_error_rate Mock upstream dependency error rate.
# TYPE upstream_request_error_rate gauge
upstream_request_error_rate{{service="payment-service",dependency="payment-gateway"}} {gateway_error_rate:.6f}
# HELP upstream_dependency_health Mock upstream dependency health score, 1 means healthy.
# TYPE upstream_dependency_health gauge
upstream_dependency_health{{service="payment-service",dependency="payment-gateway"}} {gateway_health:.6f}
upstream_dependency_health{{service="order-service",dependency="payment-service"}} {order_dependency_health:.6f}
upstream_dependency_health{{service="product-service",dependency="recommendation-service"}} {max(0.18, min(0.96, 0.66 + math.cos(elapsed / 14.0) * 0.16)):.6f}
# HELP payment_gateway_retry_exhausted_total Mock retry exhaustion count.
# TYPE payment_gateway_retry_exhausted_total gauge
payment_gateway_retry_exhausted_total{{service="payment-service"}} {payment_retry_exhausted}
# HELP payment_gateway_circuit_breaker_open_ratio Mock circuit breaker open ratio.
# TYPE payment_gateway_circuit_breaker_open_ratio gauge
payment_gateway_circuit_breaker_open_ratio{{service="payment-service"}} {circuit_breaker_open_ratio:.6f}
# HELP payment_result_pending_requests Mock pending payment result requests.
# TYPE payment_result_pending_requests gauge
payment_result_pending_requests{{service="payment-service"}} {payment_pending_requests}
# HELP frontend_first_screen_p95 Mock first screen latency.
# TYPE frontend_first_screen_p95 gauge
frontend_first_screen_p95{{page="/products"}} {frontend_first_screen:.6f}
# HELP static_bundle_download_p95 Mock static bundle download latency.
# TYPE static_bundle_download_p95 gauge
static_bundle_download_p95{{page="/products"}} {static_bundle_download:.6f}
# HELP product_detail_cache_hit_ratio Mock product detail cache hit ratio.
# TYPE product_detail_cache_hit_ratio gauge
product_detail_cache_hit_ratio{{service="product-service"}} {product_cache_hit_ratio:.6f}
# HELP recommendation_request_duration_p95 Mock recommendation latency.
# TYPE recommendation_request_duration_p95 gauge
recommendation_request_duration_p95{{service="recommendation-service"}} {recommendation_latency:.6f}
# HELP service_health_score Mock service health score.
# TYPE service_health_score gauge
service_health_score{{service="gateway-service"}} {gateway_service_health_score:.6f}
# HELP token_validation_latency_p99 Mock token validation p99 latency.
# TYPE token_validation_latency_p99 gauge
token_validation_latency_p99{{service="auth-service"}} {auth_token_validation_latency:.6f}
# HELP session_refresh_failure_ratio Mock session refresh failure ratio.
# TYPE session_refresh_failure_ratio gauge
session_refresh_failure_ratio{{service="auth-service"}} {session_refresh_failure_ratio:.6f}
# HELP dependency_success_rate Mock dependency success rate.
# TYPE dependency_success_rate gauge
dependency_success_rate{{service="order-service"}} {max(0.88, min(0.996, 0.93 + math.cos(elapsed / 24.0) * 0.02)):.6f}
# HELP db_connection_pool_active Mock active DB pool connections.
# TYPE db_connection_pool_active gauge
db_connection_pool_active{{service="product-service",pool="primary"}} {product_db_pool_active}
db_connection_pool_active{{service="mock-db-service",pool="primary"}} {db_pool_active}
# HELP db_connection_pool_waiters Mock DB pool waiters.
# TYPE db_connection_pool_waiters gauge
db_connection_pool_waiters{{service="product-service",pool="primary"}} {product_db_pool_waiters}
# HELP db_query_duration_seconds Mock DB query duration.
# TYPE db_query_duration_seconds gauge
db_query_duration_seconds{{service="product-service",db="inventory-db",operation="select_products"}} {product_db_slow_query:.6f}
# HELP process_cpu_usage Mock process CPU usage ratio.
# TYPE process_cpu_usage gauge
process_cpu_usage{{service="mock-app-service"}} {cpu_usage:.6f}
process_cpu_usage{{service="product-service"}} {min(0.95, cpu_usage + 0.18):.6f}
# HELP jvm_memory_used_bytes Mock JVM used memory.
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{{service="mock-app-service",area="heap"}} {jvm_memory}
"""



class MetricsHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/metrics":
            body = render_metrics().encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if parsed.path in ("/health", "/healthz"):
            body = b'{"status":"UP"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        self.send_response(404)
        self.end_headers()

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), MetricsHandler)
    print(f"mock-metrics-service listening on http://{HOST}:{PORT}", flush=True)
    server.serve_forever()
