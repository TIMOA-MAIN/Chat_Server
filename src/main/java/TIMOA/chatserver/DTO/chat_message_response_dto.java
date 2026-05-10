package TIMOA.chatserver.DTO;

import TIMOA.chatserver.entity.Chat_messages;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class chat_message_response_dto {

    private Long messageId;
    private Long channelId;
    private Long serverId;
    private String senderId;
    private String messageType;
    private String content;
    private String fileUrl;
    private String fileName;
    private Long fileSize;
    private String imageUrl;
    private String imageName;
    private Long imageSize;
    private LocalDateTime sentAt;
    private String nickname;

    private String fileData;


    public static chat_message_response_dto fromEntity(Chat_messages message, String nickname, String fileData) {
        return new chat_message_response_dto(
                message.getMessageId(),
                message.getChatChannel().getChannelId(),
                message.getChatChannel().getServer().getServerId(),
                message.getUser().getUserId(),
                message.getMessageType(),
                message.getContent(),
                message.getFileUrl(),
                message.getFileName(),
                message.getFileSize(),
                message.getImageUrl(),
                message.getImageName(),
                message.getImageSize(),
                message.getSentAt(),
                nickname,
                fileData
        );
    }
}