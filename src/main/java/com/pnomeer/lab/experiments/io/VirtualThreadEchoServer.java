package com.pnomeer.lab.experiments.io;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class VirtualThreadEchoServer {
    private static final int DEFAULT_PORT = 7032;
    private static final AtomicInteger CONNECTION_COUNT = new AtomicInteger();

    private VirtualThreadEchoServer() {
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        long delayMs = args.length > 1 ? Long.parseLong(args[1]) : 0L;
        System.out.println("[VirtualThreadEchoServer] starting on port " + port + ", delayMs=" + delayMs);

        try (ServerSocket serverSocket = new ServerSocket(port);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            while (true) {
                Socket socket = serverSocket.accept();
                int connId = CONNECTION_COUNT.incrementAndGet();
                executor.submit(() -> handle(socket, connId, delayMs));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handle(Socket socket, int connId, long delayMs) {
        System.out.printf("[%s] connected: #%d, thread=%s, isVirtual=%s%n",
                LocalTime.now(),
                connId,
                Thread.currentThread(),
                Thread.currentThread().isVirtual());

        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                sleep(delayMs);
                String response = "[virtual] echo: " + line + "\n";
                writer.write(response);
                writer.flush();
            }
        } catch (Exception e) {
            System.out.printf("[%s] disconnected with error: #%d, %s%n",
                    LocalTime.now(), connId, e.getMessage());
        } finally {
            System.out.printf("[%s] disconnected: #%d%n", LocalTime.now(), connId);
        }
    }

    private static void sleep(long delayMs) throws InterruptedException {
        if (delayMs > 0L) {
            Thread.sleep(delayMs);
        }
    }
}
