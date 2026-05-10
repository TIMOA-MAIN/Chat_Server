package TIMOA.chatserver.repository;

import TIMOA.chatserver.entity.Chat_channels;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public interface Chat_channels_repository extends JpaRepository<Chat_channels, Long> {
    /*
     * 특정 서버의 모든 채팅 채널 조회
     */
    List<Chat_channels> findByServerId(Long serverId);

    /*
     * 서버 ID와 채널 ID로 특정 채널 조회
     */
    Optional<Chat_channels> findByServerIdAndChannelId(Long serverId, Long channelId);

    Long countByServerId(Long serverId);

}

