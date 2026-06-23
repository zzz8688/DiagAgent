package io.github.zzz8688.diagagent.protocol;

public record ProtocolAuthConfig(
        String type,
        String headerName,
        String secretName,
        boolean required
) {

    public static ProtocolAuthConfig none() {
        return new ProtocolAuthConfig("none", null, null, false);
    }

    public static ProtocolAuthConfig bearerHeader(String headerName, String secretName) {
        return new ProtocolAuthConfig("bearer-header", headerName, secretName, true);
    }
}
