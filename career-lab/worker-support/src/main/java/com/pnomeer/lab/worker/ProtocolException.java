package com.pnomeer.lab.worker;

import java.io.IOException;

/** Stable reason codes without echoing untrusted payloads or workspace paths. */
public final class ProtocolException extends IOException {
    public ProtocolException(String reason) { super(reason); }
}
