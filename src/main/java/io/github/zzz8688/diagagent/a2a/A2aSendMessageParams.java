package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aSendMessageParams(
        Message message,
        Configuration configuration,
        Map<String, Object> metadata,
        String tenant
) {

    public A2aSendMessageParams {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
    }

    public A2aSendMessageParams(Message message,
                                Configuration configuration,
                                Map<String, Object> metadata) {
        this(message, configuration, metadata, null);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Configuration(
            List<String> acceptedOutputModes,
            Integer historyLength,
            Map<String, Object> taskPushNotificationConfig,
            Boolean returnImmediately
    ) {

        public Configuration {
            acceptedOutputModes = acceptedOutputModes == null || acceptedOutputModes.isEmpty()
                    ? null
                    : List.copyOf(acceptedOutputModes);
            returnImmediately = returnImmediately == null ? Boolean.FALSE : returnImmediately;
            taskPushNotificationConfig = taskPushNotificationConfig == null || taskPushNotificationConfig.isEmpty()
                    ? null
                    : Map.copyOf(taskPushNotificationConfig);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(
            String messageId,
            String contextId,
            String taskId,
            Role role,
            List<Part> parts,
            Map<String, Object> metadata,
            List<String> extensions,
            List<String> referenceTaskIds
    ) {

        public Message {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must not be blank");
            }
            if (role == null) {
                throw new IllegalArgumentException("role must not be null");
            }
            if (parts == null || parts.isEmpty()) {
                throw new IllegalArgumentException("parts must not be empty");
            }
            parts = List.copyOf(parts);
            metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
            extensions = extensions == null || extensions.isEmpty() ? null : List.copyOf(extensions);
            referenceTaskIds = referenceTaskIds == null || referenceTaskIds.isEmpty() ? null : List.copyOf(referenceTaskIds);
        }
    }

    public enum Role {
        ROLE_UNSPECIFIED,
        ROLE_USER,
        ROLE_AGENT
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({
            @JsonSubTypes.Type(TextPart.class),
            @JsonSubTypes.Type(DataPart.class),
            @JsonSubTypes.Type(FilePart.class)
    })
    public sealed interface Part permits TextPart, DataPart, FilePart {

        static TextPart text(String value) {
            return new TextPart(value, null);
        }

        static DataPart data(Object value) {
            return new DataPart(value, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TextPart(
            String text,
            Map<String, Object> metadata
    ) implements Part {

        public TextPart {
            if (text == null) {
                throw new IllegalArgumentException("text must not be null");
            }
            metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DataPart(
            Object data,
            Map<String, Object> metadata
    ) implements Part {

        public DataPart {
            if (data == null) {
                throw new IllegalArgumentException("data must not be null");
            }
            metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FilePart(
            FileContent file,
            Map<String, Object> metadata
    ) implements Part {

        public FilePart {
            if (file == null) {
                throw new IllegalArgumentException("file must not be null");
            }
            metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({
            @JsonSubTypes.Type(FileWithBytes.class),
            @JsonSubTypes.Type(FileWithUri.class)
    })
    public sealed interface FileContent permits FileWithBytes, FileWithUri {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileWithBytes(
            String mimeType,
            String name,
            String bytes
    ) implements FileContent {

        public FileWithBytes {
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("mimeType must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (bytes == null || bytes.isBlank()) {
                throw new IllegalArgumentException("bytes must not be blank");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileWithUri(
            String mimeType,
            String name,
            String uri
    ) implements FileContent {

        public FileWithUri {
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("mimeType must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("uri must not be blank");
            }
        }
    }
}
