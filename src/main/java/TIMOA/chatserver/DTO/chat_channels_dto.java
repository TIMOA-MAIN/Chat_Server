package TIMOA.chatserver.DTO;

import TIMOA.chatserver.entity.Chat_channels;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class chat_channels_dto {
    private Long channelId;

    private Long serverId;

    private String channelName;

    public static chat_channels_dto fromEntity(Chat_channels u) {
        return new chat_channels_dto(
                u.getChannelId(),
                u.getServer().getServerId(),
                u.getChannelName()
        );
    }
}
