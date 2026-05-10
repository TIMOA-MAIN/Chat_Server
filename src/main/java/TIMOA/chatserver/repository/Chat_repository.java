package TIMOA.chatserver.repository;

import TIMOA.chatserver.entity.Chat_messages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface Chat_repository extends JpaRepository<Chat_messages, Long> {
    // 채널별 최신 메시지 페이지네이션으로 가져오기
    @Query("SELECT m FROM Chat_messages m WHERE m.chatChannel.channelId = :channelId ORDER BY m.sentAt DESC")
    Page<Chat_messages> findByChannelIdOrderBySentAtDesc(
            @Param("channelId") Long channelId,
            Pageable pageable
    );

    // 특정 시점 이전 메시지 가져오기 (무한 스크롤용)
    @Query("SELECT m FROM Chat_messages m WHERE m.chatChannel.channelId = :channelId AND m.sentAt < :beforeTime ORDER BY m.sentAt DESC")
    List<Chat_messages> findByChannelIdAndSentAtBeforeOrderBySentAtDesc(
            @Param("channelId") Long channelId,
            @Param("beforeTime") LocalDateTime beforeTime,
            Pageable pageable
    );

    List<Chat_messages> findByChannelId(Long channelId);
}
