package io.github.zzz8688.diagagent.protocol;

public enum ProtocolTransportConnectionState {
    REGISTERED,
    CONNECTING,
    RECONNECTING,
    CONNECTED,
    DEGRADED,
    AUTH_REQUIRED,
    FAILED,
    CLOSED,
    DISABLED
}
