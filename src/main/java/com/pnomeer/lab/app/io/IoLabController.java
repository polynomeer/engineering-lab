package com.pnomeer.lab.app.io;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/lab/io")
public class IoLabController {
    private final ExecutorService ioVirtualThreadExecutor;

    public IoLabController(@Qualifier("ioVirtualThreadExecutor") ExecutorService ioVirtualThreadExecutor) {
        this.ioVirtualThreadExecutor = ioVirtualThreadExecutor;
    }

    @GetMapping(value = "/virtual-thread/echo", produces = MediaType.TEXT_PLAIN_VALUE)
    public CompletableFuture<String> virtualThreadEcho(
            @RequestParam String msg,
            @RequestParam(defaultValue = "100") long delayMs) {
        long safeDelayMs = Math.max(0L, delayMs);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(safeDelayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("virtual-thread echo interrupted", ex);
            }
            return "[virtual-thread] " + msg + " | thread=" + Thread.currentThread();
        }, ioVirtualThreadExecutor);
    }

    @GetMapping(value = "/reactive/echo", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> reactiveEcho(
            @RequestParam String msg,
            @RequestParam(defaultValue = "100") long delayMs) {
        long safeDelayMs = Math.max(0L, delayMs);
        return Mono.delay(Duration.ofMillis(safeDelayMs))
                .map(ignored -> "[reactive] " + msg + " | thread=" + Thread.currentThread());
    }
}
