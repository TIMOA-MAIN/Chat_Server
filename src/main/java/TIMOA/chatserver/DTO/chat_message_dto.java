package TIMOA.chatserver.DTO;

import TIMOA.chatserver.entity.Chat_messages;
import TIMOA.chatserver.entity.Users;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class chat_message_dto {

    private Long serverId;
    private Long channelId;
    private String userId;
    private String content;

    public static chat_message_dto fromEntity(Chat_messages m) {
        return new chat_message_dto(
                m.getChatChannel().getServerId(),
                m.getChannelId(),
                m.getUser().getUserId(),
                m.getContent());
    }
}