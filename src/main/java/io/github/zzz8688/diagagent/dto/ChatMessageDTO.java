package io.github.zzz8688.diagagent.dto;

public class ChatMessageDTO {

    private String type;
    private String text;

    public ChatMessageDTO() {
    }

    public ChatMessageDTO(String type, String text) {
        this.type = type;
        this.text = text;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public static final class Builder {
        private String type;
        private String text;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public ChatMessageDTO build() {
            return new ChatMessageDTO(type, text);
        }
    }
}
