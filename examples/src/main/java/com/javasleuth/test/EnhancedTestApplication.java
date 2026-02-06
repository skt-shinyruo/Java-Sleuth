package com.javasleuth.test;

import java.util.concurrent.atomic.AtomicInteger;

// 增强版示例应用：包含多线程、业务链路、计算与异常场景，适合演示 watch/trace/tt/stack/redefine 等命令。
public class EnhancedTestApplication {
    private static volatile boolean running = true;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final BusinessLogic businessLogic = new BusinessLogic();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Enhanced Test Application Started");
        System.out.println("PID: " + getCurrentPid());
        System.out.println("Features: Method calls, business logic, calculations, error scenarios");
        System.out.println();

        EnhancedTestApplication app = new EnhancedTestApplication();
        app.start();
    }

    public void start() throws InterruptedException {
        // 创建多个工作线程，用于演示不同场景
        Thread workerThread = new Thread(this::workerLoop, "WorkerThread");
        Thread calculatorThread = new Thread(this::calculatorLoop, "CalculatorThread");
        Thread errorThread = new Thread(this::errorLoop, "ErrorThread");

        workerThread.start();
        calculatorThread.start();
        errorThread.start();

        // 主监控循环
        while (running) {
            Thread.sleep(1000);
            System.out.println("Application running... Counter: " + counter.get());
        }

        workerThread.join();
        calculatorThread.join();
        errorThread.join();
        System.out.println("Enhanced Test Application Stopped");
    }

    private void workerLoop() {
        while (running) {
            try {
                Thread.sleep(3000);
                processBusinessTask();
                counter.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void calculatorLoop() {
        while (running) {
            try {
                Thread.sleep(5000);
                performCalculations();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void errorLoop() {
        while (running) {
            try {
                Thread.sleep(8000);
                simulateErrorScenarios();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void processBusinessTask() {
        long startTime = System.currentTimeMillis();

        // 调用链：用于 tracing 演示
        String result = businessLogic.processOrder("ORDER-" + counter.get());
        businessLogic.saveResult(result);
        businessLogic.sendNotification(result);

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("Business task completed in " + duration + "ms: " + result);
    }

    public void performCalculations() {
        int value = counter.get();

        // 多类型计算：便于观测不同方法/耗时
        long fibonacci = calculateFibonacci(value % 20 + 10);
        double sqrt = Math.sqrt(value * 1000);
        boolean isPrime = isPrime(value + 100);

        System.out.println("Calculations - Fibonacci: " + fibonacci + ", Sqrt: " +
                         String.format("%.2f", sqrt) + ", IsPrime: " + isPrime);
    }

    public void simulateErrorScenarios() {
        try {
            int scenario = counter.get() % 3;
            switch (scenario) {
                case 0:
                    businessLogic.riskyOperation("test");
                    break;
                case 1:
                    businessLogic.processInvalidData(null);
                    break;
                case 2:
                    businessLogic.connectToExternalService("invalid-url");
                    break;
            }
        } catch (Exception e) {
            System.out.println("Handled error scenario: " + e.getMessage());
        }
    }

    // 可被 redefine 的方法：用于 hot-reload 演示
    public String getGreeting() {
        return "Hello from Java-Sleuth Test Application v1.0!";
    }

    // 适合 watch/trace 的递归方法
    public long calculateFibonacci(int n) {
        if (n <= 1) return n;
        return calculateFibonacci(n - 1) + calculateFibonacci(n - 2);
    }

    public boolean isPrime(int n) {
        if (n <= 1) return false;
        if (n <= 3) return true;
        if (n % 2 == 0 || n % 3 == 0) return false;

        for (int i = 5; i * i <= n; i += 6) {
            if (n % i == 0 || n % (i + 2) == 0) {
                return false;
            }
        }
        return true;
    }

    private static String getCurrentPid() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    }

    public static void shutdown() {
        running = false;
    }

    // 业务逻辑示例：用于方法链路/异常场景演示
    private static class BusinessLogic {

        public String processOrder(String orderId) {
            simulateWork(100, 300);
            return "Processed: " + orderId + " at " + System.currentTimeMillis();
        }

        public void saveResult(String result) {
            simulateWork(50, 150);
            // 模拟数据库写入
        }

        public void sendNotification(String message) {
            simulateWork(25, 75);
            // 模拟发送通知
        }

        public void riskyOperation(String input) throws RuntimeException {
            simulateWork(10, 50);
            if ("test".equals(input) && Math.random() < 0.3) {
                throw new RuntimeException("Simulated runtime error");
            }
        }

        public void processInvalidData(String data) throws IllegalArgumentException {
            if (data == null) {
                throw new IllegalArgumentException("Data cannot be null");
            }
        }

        public void connectToExternalService(String url) throws Exception {
            simulateWork(100, 200);
            if ("invalid-url".equals(url)) {
                throw new Exception("Failed to connect to external service");
            }
        }

        private void simulateWork(int minMs, int maxMs) {
            try {
                long sleepTime = minMs + (long) (Math.random() * (maxMs - minMs));
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

