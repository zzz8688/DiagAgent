package io.github.zzz8688.diagagent.protocol;

public interface ProtocolTransportFactory {

    ProtocolTransportSpec create(ProtocolServerConfig config);

    ProtocolTransportConnection createConnection(ProtocolServerConfig config);
}
