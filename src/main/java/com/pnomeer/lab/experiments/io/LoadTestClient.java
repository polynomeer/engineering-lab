package com.pnomeer.lab.experiments.io;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class LoadTestClient {
    private LoadTestClient() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 7031;
        int clients = args.length > 2 ? Integer.parseInt(args[2]) : 200;
        int messagesPerClient = args.length > 3 ? Integer.parseInt(args[3]) : 20;

        System.out.printf(
                "Load test start: host=%s, port=%d, clients=%d, messagesPerClient=%d%n",
                host,
                port,
                clients,
                messagesPerClient);

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(clients, 300));
        CountDownLatch latch = new CountDownLatch(clients);
        AtomicLong totalLatencyNanos = new AtomicLong();
        List<Future<Void>> futures = new ArrayList<>();
        Instant start = Instant.now();

        for (int i = 0; i < clients; i++) {
            final int clientId = i;
            futures.add(pool.submit(() -> {
                runClient(host, port, clientId, messagesPerClient, totalLatencyNanos);
                latch.countDown();
                return null;
            }));
        }

        latch.await();
        for (Future<Void> future : futures) {
            future.get();
        }
        pool.shutdown();

        Instant end = Instant.now();
        long totalMessages = (long) clients * messagesPerClient;
        double avgMs = totalMessages == 0L ? 0.0d : totalLatencyNanos.get() / 1_000_000.0d / totalMessages;

        System.out.println("--------------------------------------------------");
        System.out.println("Total messages: " + totalMessages);
        System.out.println("Elapsed: " + Duration.between(start, end).toMillis() + " ms");
        System.out.printf("Average latency per message: %.3f ms%n", avgMs);
    }

    private static void runClient(
            String host,
            int port,
            int clientId,
            int messagesPerClient,
            AtomicLong totalLatencyNanos) throws IOException {
        try (Socket socket = new Socket(host, port);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            for (int i = 0; i < messagesPerClient; i++) {
                String message = "client-" + clientId + "-msg-" + i;
                long t1 = System.nanoTime();
                writer.write(message);
                writer.write("\n");
                writer.flush();

                String response = reader.readLine();
                long t2 = System.nanoTime();
                if (response == null) {
                    throw new EOFException("server closed connection");
                }
                totalLatencyNanos.addAndGet(t2 - t1);
            }
        }
    }
}
