package io.github.zzz8688.diagagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "diag-agent")
public class DiagAgentProperties {

    private System system = new System();

    public System getSystem() {
        return system;
    }

    public void setSystem(System system) {
        this.system = system;
    }

    @Data
    public static class System {
        private Chat mainAgent = new Chat("diag-agent-main-prompt", "DEFAULT_GROUP", 20000L);
        private Chat appAgent = new Chat("diag-agent-app-prompt", "DEFAULT_GROUP", 20000L);
        private Chat dbAgent = new Chat("diag-agent-db-prompt", "DEFAULT_GROUP", 20000L);
        private Chat networkAgent = new Chat("diag-agent-network-prompt", "DEFAULT_GROUP", 20000L);
        private Chat criticAgent = new Chat("diag-agent-critic-prompt", "DEFAULT_GROUP", 20000L);
        private Chat memoryMaintenanceAgent = new Chat("diag-agent-memory-maintenance-prompt", "DEFAULT_GROUP", 20000L);

        public Chat getMainAgent() {
            return mainAgent;
        }

        public void setMainAgent(Chat mainAgent) {
            this.mainAgent = mainAgent;
        }

        public Chat getAppAgent() {
            return appAgent;
        }

        public void setAppAgent(Chat appAgent) {
            this.appAgent = appAgent;
        }

        public Chat getDbAgent() {
            return dbAgent;
        }

        public void setDbAgent(Chat dbAgent) {
            this.dbAgent = dbAgent;
        }

        public Chat getNetworkAgent() {
            return networkAgent;
        }

        public void setNetworkAgent(Chat networkAgent) {
            this.networkAgent = networkAgent;
        }

        public Chat getCriticAgent() {
            return criticAgent;
        }

        public void setCriticAgent(Chat criticAgent) {
            this.criticAgent = criticAgent;
        }

        public Chat getMemoryMaintenanceAgent() {
            return memoryMaintenanceAgent;
        }

        public void setMemoryMaintenanceAgent(Chat memoryMaintenanceAgent) {
            this.memoryMaintenanceAgent = memoryMaintenanceAgent;
        }

        @Data
        public static class Chat {
            private String dataId;
            private String group;
            private long timeoutMs;

            public Chat() {
            }

            public Chat(String dataId, String group, long timeoutMs) {
                this.dataId = dataId;
                this.group = group;
                this.timeoutMs = timeoutMs;
            }

            public String getDataId() {
                return dataId;
            }

            public void setDataId(String dataId) {
                this.dataId = dataId;
            }

            public String getGroup() {
                return group;
            }

            public void setGroup(String group) {
                this.group = group;
            }

            public long getTimeoutMs() {
                return timeoutMs;
            }

            public void setTimeoutMs(long timeoutMs) {
                this.timeoutMs = timeoutMs;
            }
        }
    }
}
