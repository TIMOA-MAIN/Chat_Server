package TIMOA.chatserver.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class chat_update_dto {
    private Long messageId;
    private Long channelId;
    private Long serverId;
    private String senderId;
    private String orderId;
    private String messageType;
    private String content;
}
