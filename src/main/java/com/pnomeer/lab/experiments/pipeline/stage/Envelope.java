package com.pnomeer.lab.experiments.pipeline.stage;

import java.util.Objects;

public final class Envelope<T> {
    public enum Kind {
        DATA,
        END
    }

    private static final Envelope<?> END = new Envelope<>(Kind.END, null);

    private final Kind kind;
    private final T payload;

    private Envelope(Kind kind, T payload) {
        this.kind = kind;
        this.payload = payload;
    }

    public static <T> Envelope<T> data(T payload) {
        return new Envelope<>(Kind.DATA, Objects.requireNonNull(payload, "payload must not be null"));
    }

    @SuppressWarnings("unchecked")
    public static <T> Envelope<T> end() {
        return (Envelope<T>) END;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isData() {
        return kind == Kind.DATA;
    }

    public boolean isEnd() {
        return kind == Kind.END;
    }

    public T getPayload() {
        if (!isData()) {
            throw new IllegalStateException("END envelope has no payload");
        }
        return payload;
    }
}
