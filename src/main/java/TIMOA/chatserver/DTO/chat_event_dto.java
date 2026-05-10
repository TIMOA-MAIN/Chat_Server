package TIMOA.chatserver.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class chat_event_dto {

    private String type;
    private Long serverId;
    private Long channelId;
    private Object data;
    private users_dto sender;
    private LocalDateTime timestamp;
    private String targetUserId;
}