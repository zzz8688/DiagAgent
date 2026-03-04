package io.github.zzz8688.diagagent.store;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Document(collection = "chat_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMemoryDoc {

    @Id
    private String id;

    private String memoryId;

    private String content; 

    private Date createdAt;

    private Date updatedAt;

    public ChatMemoryDoc(String memoryId, String content) {
        this.memoryId = memoryId;
        this.content = content;
        this.createdAt = new Date();
        this.updatedAt = new Date();
    }
}
