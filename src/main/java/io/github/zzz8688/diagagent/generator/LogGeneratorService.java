package io.github.zzz8688.diagagent.generator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class LogGeneratorService {

    private final Random random = new Random();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private static final String LOG_DIR = "logs/";

    private static final double ERROR_RATE = 0.05; 
    private static final int TRAFFIC_RATE_MS = 1000; 

    public LogGeneratorService() {

        new java.io.File(LOG_DIR).mkdirs();
    }

    public void generateTraffic() {

    }

    private void simulateUserRequest() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String userId = "user_" + random.nextInt(1000);

        try {

            appendLog("frontend", traceId, "INFO", "Received request from " + userId + " for /checkout");
            sleep(random.nextInt(50));

            appendLog("order-service", traceId, "INFO", "Processing order for " + userId);

            boolean productSuccess = callProductService(traceId);
            if (!productSuccess) {
                appendLog("order-service", traceId, "ERROR", "Failed to call product-service: Connection timeout");
                appendLog("frontend", traceId, "ERROR", "Order creation failed: internal dependency error");
                return;
            }

            boolean paymentSuccess = callPaymentService(traceId);
            if (!paymentSuccess) {
                appendLog("order-service", traceId, "ERROR", "Payment failed for order");
                appendLog("frontend", traceId, "ERROR", "Order creation failed: payment declined or error");
                return;
            }

            appendLog("order-service", traceId, "INFO", "Order created successfully");
            appendLog("frontend", traceId, "INFO", "Request completed successfully");

        } catch (Exception e) {
            appendLog("system", traceId, "ERROR", "Unexpected error in simulation: " + e.getMessage());
        }
    }

    private boolean callProductService(String traceId) {
        appendLog("product-service", traceId, "INFO", "Checking inventory for items");
        sleep(random.nextInt(100));

        if (random.nextDouble() < ERROR_RATE) {
            appendLog("product-service", traceId, "WARN", "Database connection pool is low");
            sleep(2000); 
            appendLog("product-service", traceId, "ERROR", "Connection to InventoryDB timed out after 2000ms");
            return false;
        }

        appendLog("product-service", traceId, "INFO", "Inventory check passed");
        return true;
    }

    private boolean callPaymentService(String traceId) {
        appendLog("payment-service", traceId, "INFO", "Initiating payment transaction");
        sleep(random.nextInt(200));

        if (random.nextDouble() < ERROR_RATE) {
            appendLog("payment-service", traceId, "ERROR", "Payment gateway 503 Service Unavailable");
            return false;
        }

        appendLog("payment-service", traceId, "INFO", "Payment transaction completed");
        return true;
    }

    private void appendLog(String serviceName, String traceId, String level, String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        String logLine = String.format("%s %-5s [%s,%s] %s", timestamp, level, serviceName, traceId, message);

        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(LOG_DIR + serviceName + ".log", true)))) {
            out.println(logLine);
        } catch (IOException e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
