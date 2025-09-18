package com.javasleuth.test;

public class TestApplication {
    private static volatile boolean running = true;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("Test Application Started - PID: " +
            java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);

        // Simulate some work
        Thread worker = new Thread(() -> {
            int counter = 0;
            while (running) {
                try {
                    Thread.sleep(2000);
                    counter++;
                    doSomeWork(counter);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "WorkerThread");

        worker.start();

        // Keep main thread alive
        while (running) {
            Thread.sleep(1000);
        }

        worker.join();
        System.out.println("Test Application Stopped");
    }

    private static void doSomeWork(int iteration) {
        // Simulate some CPU work
        long startTime = System.currentTimeMillis();
        calculatePrimes(1000);
        long endTime = System.currentTimeMillis();

        System.out.println("Iteration " + iteration + " completed in " + (endTime - startTime) + "ms");
    }

    private static void calculatePrimes(int max) {
        for (int i = 2; i <= max; i++) {
            if (isPrime(i)) {
                // Just counting primes
            }
        }
    }

    private static boolean isPrime(int n) {
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

    public static void shutdown() {
        running = false;
    }
}