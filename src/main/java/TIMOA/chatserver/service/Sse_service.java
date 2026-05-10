package TIMOA.chatserver.service;

import TIMOA.chatserver.DTO.users_dto;
import TIMOA.chatserver.entity.Chat_messages;
import TIMOA.chatserver.entity.Server_members;
import TIMOA.chatserver.repository.Server_member_repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class Sse_service {

    // @userId 형태의 멘션을 찾는 정규표현식
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([a-zA-Z0-9_]+)");

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Server_member_repository server_member_repository;

    // 사용자별 구독자 관리 (기존 서버/채널별 대신)
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> userSubscribers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService keepAliveExecutor = Executors.newScheduledThreadPool(1);

    public Sse_service() {
        //keepAliveExecutor.scheduleAtFixedRate(this::sendKeepAlive, 60, 60, TimeUnit.SECONDS);
        log.info("SSE 서비스 시작 - Keep-alive 비활성화 (테스트용)");
    }

    /*
     * 사용자별 SSE 연결 생성
     */
    public SseEmitter createUserConnection(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            log.error("userId가 null이거나 비어있습니다");
            throw new IllegalArgumentException("userId cannot be null or empty");
        }

        SseEmitter emitter = new SseEmitter(300000L);

        try {
            Thread.sleep(500);

            log.info("연결됐다");
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("Connected for user " + userId));
        } catch (IOException e) {
            log.error("사용자 {}의 초기 SSE 메시지 전송 오류: {}", userId, e.getMessage());
            emitter.completeWithError(e);
            return emitter;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // 동기화 블록으로 원자성 보장
        // 중복 연결 방지 로직
        synchronized (userSubscribers) {
            // 기존 연결들을 즉시 정리
            CopyOnWriteArrayList<SseEmitter> existing = userSubscribers.get(userId);
            if (existing != null && !existing.isEmpty()) {
                log.info("사용자 {}의 기존 연결 {}개를 정리합니다", userId, existing.size());

                // 기존 연결들을 완료시키고 즉시 제거
                List<SseEmitter> toRemove = new ArrayList<>(existing);
                for (SseEmitter old : toRemove) {
                    try {
                        old.complete();
                    } catch (Exception e) {
                        log.debug("기존 연결 정리 중 오류 (무시): {}", e.getMessage());
                    }
                }

                // 기존 리스트를 완전히 정리
                existing.clear();
                userSubscribers.remove(userId);
            }

            // 새로운 리스트 생성하고 새 연결 추가
            CopyOnWriteArrayList<SseEmitter> newList = new CopyOnWriteArrayList<>();
            newList.add(emitter);
            userSubscribers.put(userId, newList);

            log.info("사용자 {}의 새로운 SSE 연결 (전체 사용자 연결 수: {})",
                    userId, getTotalUserConnections());
        }

        // 연결 종료 시 정리
        emitter.onCompletion(() -> {
            removeUserEmitter(userId, emitter);
            log.debug("사용자 {}의 SSE 연결이 완료되었습니다", userId);
        });

        emitter.onTimeout(() -> {
            removeUserEmitter(userId, emitter);
            log.info("사용자 {}의 SSE 연결이 타임아웃되었습니다", userId);
        });

        emitter.onError((throwable) -> {
            removeUserEmitter(userId, emitter);
            if (throwable instanceof IOException &&
                    (throwable.getMessage().contains("현재 연결은") ||
                            throwable.getMessage().contains("Connection reset") ||
                            throwable.getMessage().contains("Broken pipe"))) {
                log.debug("사용자 {}의 SSE 연결이 클라이언트에 의해 종료됨: {}", userId, throwable.getMessage());
            } else {
                log.error("사용자 {}의 SSE 연결 오류: {}", userId, throwable.getMessage());
            }
        });

        return emitter;
    }

    /*
     * 개인 사용자에게 이벤트 유니캐스트
     */
    public void unicateToUserErrorMessage(String userId, String eventType, Object data) {
        try{
            log.info("사용자 {}에게 유니캐스트 중", userId);

            Map<String, Object> message = new HashMap<>();
            message.put("type", eventType);
            message.put("userId", userId);
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);

            CopyOnWriteArrayList<SseEmitter> userEmitters = userSubscribers.get(userId);
            if (userEmitters != null && !userEmitters.isEmpty()) {
                log.info("사용자 {}에게 유니캐스트 중", userId);
                try {
                    sendToUserEmitters(userEmitters, eventType, jsonMessage, userId);
                } catch (Exception userEx) {
                    log.warn("사용자 {}에게 브로드캐스트 실패: {}", userId, userEx.getMessage());
                }
            }

            log.info("사용자 {}에게 유니캐스트 성공", userId);

        } catch (Exception e) {
            log.error("사용자 {}에게 브로드캐스트 실패: {} - {}",
                    userId, e.getClass().getSimpleName(),
                    e.getMessage() != null ? e.getMessage() : "메시지 없음", e);
        }
    }


    /*
     * 서버의 모든 멤버에게 이벤트 브로드캐스트
     */
    public void broadcastToServerMembers(Long serverId, String eventType, Object data) {
        try {
            // 서버 멤버 목록 조회
            List<String> serverMembers = server_member_repository.findByServerId(serverId)
                    .stream()
                    .map(Server_members::getUserId)
                    .toList();

            log.info("서버 {}의 멤버 목록: {}", serverId, serverMembers.toString());

            if (serverMembers.isEmpty()) {
                log.warn("서버 {}에 활성화된 멤버가 없습니다", serverId);
                return;
            }

            Map<String, Object> message = new HashMap<>();
            message.put("type", eventType);
            message.put("serverId", serverId);
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);

            // 각 서버 멤버에게 개별 전송
            int successCount = 0;
            int failCount = 0;
            for (String userId : serverMembers) {
                CopyOnWriteArrayList<SseEmitter> userEmitters = userSubscribers.get(userId);
                if (userEmitters != null && !userEmitters.isEmpty()) {
                    log.info("서버 {}의 사용자 {}에게 {}개 연결로 브로드캐스트 중",
                            serverId, userId, userEmitters.size());

                    try {
                        sendToUserEmitters(userEmitters, eventType, jsonMessage, userId);
                        successCount++;
                    } catch (Exception userEx) {
                        failCount++;
                        log.warn("사용자 {}에게 브로드캐스트 실패: {}", userId, userEx.getMessage());
                    }
                } else {
                    log.debug("서버 {}의 사용자 {}에게 활성화된 SSE 연결이 없습니다", serverId, userId);
                    failCount++;
                }
            }

            log.info("서버 브로드캐스트 완료: {} -> 서버 {}의 {}명 사용자 성공, {}명 실패",
                    eventType, serverId, successCount, failCount);

        } catch (Exception e) {
            log.error("서버 {}에 브로드캐스트 오류: {} - {}",
                    serverId, e.getClass().getSimpleName(),
                    e.getMessage() != null ? e.getMessage() : "메시지 없음", e);
        }
    }

    /*
     * 채널의 모든 멤버에게 이벤트 브로드캐스트
     */
    public void broadcastToChannelMembers(Long serverId, Long channelId, String eventType, Object data) {
        try {
            // 서버 멤버 = 채널 멤버 (현재 구조에서)
            List<String> channelMembers = server_member_repository.findByServerId(serverId)
                    .stream()
                    .map(Server_members::getUserId)
                    .toList();

            if (channelMembers.isEmpty()) {
                log.warn("서버 {}의 채널 {}에 활성화된 멤버가 없습니다", serverId, channelId);
                return;
            }

            Map<String, Object> message = new HashMap<>();
            message.put("type", eventType);
            message.put("serverId", serverId);
            message.put("channelId", channelId);
            message.put("data", data);
            message.put("timestamp", System.currentTimeMillis());

            String jsonMessage = objectMapper.writeValueAsString(message);

            // 각 채널 멤버에게 개별 전송
            int successCount = 0;
            int failCount = 0;
            for (String userId : channelMembers) {
                CopyOnWriteArrayList<SseEmitter> userEmitters = userSubscribers.get(userId);
                if (userEmitters != null && !userEmitters.isEmpty()) {
                    log.debug("채널 {} (서버 {})의 사용자 {}에게 {}개 연결로 브로드캐스트 중",
                            channelId, serverId, userId, userEmitters.size());

                    try {
                        sendToUserEmitters(userEmitters, eventType, jsonMessage, userId);
                        successCount++;
                    } catch (Exception userEx) {
                        failCount++;
                        log.warn("사용자 {}에게 브로드캐스트 실패: {}", userId, userEx.getMessage());
                    }
                } else {
                    log.debug("채널 {} (서버 {})의 사용자 {}에게 활성화된 SSE 연결이 없습니다",
                            channelId, serverId, userId);
                    failCount++;
                }
            }

            users_dto sender_data = null;
            if (data instanceof Chat_messages) {
                sender_data = users_dto.fromEntity(((Chat_messages) data).getUser());
            }

            // 메시지 생성 이벤트인 경우 멘션 확인 및 토스트 알림 전송
            if ("MESSAGE_CREATE".equals(eventType) && data instanceof Chat_messages) {
                send_mention_notifications((Chat_messages) data, channelMembers, sender_data);
            }

            log.info("채널 브로드캐스트 완료: {} -> 채널 {} (서버 {})의 {}명 사용자 성공, {}명 실패",
                    eventType, channelId, serverId, successCount, failCount);

        } catch (Exception e) {
            log.error("서버 {}의 채널 {}에 브로드캐스트 오류: {}", serverId, channelId, e.getMessage());
        }
    }

    // Keep-alive 시스템
    private void sendKeepAlive() {
        try {
            if (userSubscribers.isEmpty()) {
                log.info("활성화된 사용자 연결이 없습니다");
                return;
            }

            log.info("Keep-alive 시작: 총 {}명의 사용자 (60초 간격)", userSubscribers.size());

            for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : userSubscribers.entrySet()) {
                if (entry == null) {
                    log.warn("userSubscribers entry가 null입니다");
                    continue;
                }

                String userId = entry.getKey();
                CopyOnWriteArrayList<SseEmitter> emitters = entry.getValue();

                if (userId == null) {
                    log.warn("userId가 null인 entry가 있습니다");
                    continue;
                }

                if (emitters == null) {
                    log.warn("사용자 {}의 emitters가 null입니다", userId);
                    continue;
                }

                try {
                    sendKeepAliveToUserEmitters(emitters, userId);
                } catch (Exception userException) {
                    log.error("사용자 {}의 Keep-alive 처리 중 오류: {} - {}",
                            userId,
                            userException.getClass().getSimpleName(),
                            userException.getMessage() != null ? userException.getMessage() : "메시지 없음",
                            userException);
                }
            }

            int totalConnections = getTotalUserConnections();
            if (totalConnections > 0) {
                log.info("{}개 사용자 연결에 Keep-alive 신호 전송 완료 (60초 간격)", totalConnections);
            }

        } catch (Exception e) {
            log.error("Keep-alive 전체 처리 중 오류: {} - {}",
                    e.getClass().getSimpleName(),
                    e.getMessage() != null ? e.getMessage() : "메시지 없음", e);
        }
    }

    private void sendKeepAliveToUserEmitters(CopyOnWriteArrayList<SseEmitter> emitters, String userId) {
        if (emitters == null) {
            log.warn("사용자 {}의 emitters가 null입니다", userId);
            return;
        }

        if (emitters.isEmpty()) {
            log.debug("사용자 {}의 emitters가 비어있습니다", userId);
            return;
        }

        log.debug("사용자 {}의 Keep-alive 시작: {}개 연결", userId, emitters.size());

        int activeCount = 0;
        int removedCount = 0;

        // 제거할 emitter들을 별도로 수집
        List<SseEmitter> toRemove = new ArrayList<>();

        // 스냅샷 기반 반복 (Iterator 사용하지 않음)
        for (SseEmitter emitter : emitters) {
            if (emitter == null) {
                log.warn("사용자 {}에서 null emitter 발견", userId);
                toRemove.add(emitter);
                continue;
            }

            try {
                // emitter 상태 확인 후 전송
                try {
                    emitter.send(SseEmitter.event()
                            .name("ping")
                            .data("keep-alive"));
                    activeCount++;
                } catch (IllegalStateException stateEx) {
                    // 이미 완료된 emitter는 제거 대상에 추가
                    log.debug("사용자 {}의 완료된 emitter 제거 예정: {}", userId, stateEx.getMessage());
                    toRemove.add(emitter);
                    continue;
                }

            } catch (Exception e) {
                toRemove.add(emitter);

                if (e instanceof IOException && e.getMessage() != null && (
                        e.getMessage().contains("Connection reset") ||
                                e.getMessage().contains("Broken pipe") ||
                                e.getMessage().contains("현재 연결은"))) {
                    log.debug("사용자 {}의 Keep-alive 연결이 클라이언트에 의해 종료됨: {}", userId, e.getMessage());
                } else {
                    log.warn("사용자 {}의 Keep-alive 중 예상치 못한 오류: {} - {}",
                            userId,
                            e.getClass().getSimpleName(),
                            e.getMessage() != null ? e.getMessage() : "메시지 없음");
                }
            }
        }

        // 제거할 emitter들을 일괄 제거
        if (!toRemove.isEmpty()) {
            try {
                emitters.removeAll(toRemove);
                removedCount = toRemove.size();
                log.debug("사용자 {}의 Keep-alive 완료: 활성={}개, 제거={}개", userId, activeCount, removedCount);
            } catch (Exception removeException) {
                log.debug("사용자 {}의 emitter 일괄 제거 중 예외 발생: {}", userId,
                        removeException.getMessage() != null ? removeException.getMessage() : "메시지 없음");
            }
        }
    }

    /*
     * 실제 전송 헬퍼 메서드
     */
    private void sendToUserEmitters(CopyOnWriteArrayList<SseEmitter> emitters, String eventType, String data, String userId) {
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failCount = new AtomicInteger(0);

        // 제거할 emitter들을 별도로 수집
        List<SseEmitter> toRemove = new ArrayList<>();

        // 스냅샷 기반 반복 (Iterator 사용하지 않음)
        for (SseEmitter emitter : emitters) {
            if (emitter == null) {
                toRemove.add(emitter);
                failCount.incrementAndGet();
                continue;
            }

            try {
                SseEmitter sseEmitter = new SseEmitter();
                emitter.send(sseEmitter.event().name(eventType)
                        .data(data));
                successCount.incrementAndGet();
            } catch (IOException ioEx) {
                failCount.incrementAndGet();
                toRemove.add(emitter);

                if (ioEx.getMessage() != null && (
                        ioEx.getMessage().contains("Connection reset") ||
                                ioEx.getMessage().contains("Broken pipe") ||
                                ioEx.getMessage().contains("현재 연결은") ||
                                ioEx.getMessage().contains("호스트 시스템의 소프트웨어"))) {
                    log.debug("사용자 {}의 SSE 연결이 클라이언트에 의해 종료됨", userId);
                } else {
                    log.debug("사용자 {}의 SSE 연결 중단: {}", userId, ioEx.getMessage());
                }
            } catch (Exception e) {
                failCount.incrementAndGet();
                toRemove.add(emitter);
                log.warn("사용자 {}에게 SSE 메시지 전송 중 예상치 못한 오류: {} - {}",
                        userId,
                        e.getClass().getSimpleName(),
                        e.getMessage() != null ? e.getMessage() : "메시지 없음");
            }
        }

        // 제거할 emitter들을 일괄 제거
        if (!toRemove.isEmpty()) {
            try {
                emitters.removeAll(toRemove);
            } catch (Exception removeException) {
                log.debug("사용자 {}의 emitter 일괄 제거 중 예외: {}", userId,
                        removeException.getMessage() != null ? removeException.getMessage() : "메시지 없음");
            }
        }

        if (successCount.get() > 0 || failCount.get() > 0) {
            log.debug("📤 사용자 {}에게 전송 (성공: {}개, 실패: {}개)", userId, successCount.get(), failCount.get());
        }
    }

    /*
     * 연결 종료 헬퍼 메서드
     */
    private void removeUserEmitter(String userId, SseEmitter emitter) {
        if (userId == null) return;

        synchronized (userSubscribers) {
            CopyOnWriteArrayList<SseEmitter> userEmitters = userSubscribers.get(userId);
            if (userEmitters != null) {
                userEmitters.remove(emitter);
                if (userEmitters.isEmpty()) {
                    userSubscribers.remove(userId);
                    log.debug("사용자 {}의 모든 연결이 제거되었습니다", userId);
                }
            }
        }
    }

    private int getTotalUserConnections() {
        try {
            return userSubscribers.values().stream()
                    .filter(Objects::nonNull)
                    .mapToInt(List::size)
                    .sum();
        } catch (Exception e) {
            log.error("전체 사용자 연결 수 계산 중 오류: {} - {}",
                    e.getClass().getSimpleName(),
                    e.getMessage() != null ? e.getMessage() : "메시지 없음", e);
            return 0;
        }
    }

    /*
     * 연결 상태 조회
     */
    public Map<String, Object> getActiveSubscriptions() {
        Map<String, Object> status = new HashMap<>();
        status.put("totalUsers", userSubscribers.size());
        status.put("totalConnections", getTotalUserConnections());

        Map<String, Integer> userConnections = new HashMap<>();
        userSubscribers.forEach((userId, emitters) ->
                userConnections.put(userId, emitters.size()));
        status.put("userConnections", userConnections);

        return status;
    }

    public void shutdown() {
        if (!keepAliveExecutor.isShutdown()) {
            keepAliveExecutor.shutdown();
            try {
                if (!keepAliveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    keepAliveExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                keepAliveExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("SSE 서비스 종료 완료");
    }


    /*
     * 멘션된 사용자들에게 토스트 알림 전송
     */
    public void send_mention_notifications(Chat_messages message, List<String> server_members, users_dto sender_data) {
        try {
            String content = message.getContent();
            String sender_id = message.getUser().getUserId();

            if (content == null || content.trim().isEmpty()) {
                log.trace("메시지 내용이 비어있어 멘션 처리 생략: 메시지 ID {}", message.getMessageId());
                return;
            }

            // 메시지에서 멘션된 사용자들 추출
            List<String> mentioned_users = extract_mentioned_users(content);

            if (mentioned_users.isEmpty()) {
                log.trace("멘션된 사용자 없음: 메시지 ID {}", message.getMessageId());
                return;
            }

            // 멘션된 사용자 중에서 실제 서버 멤버인 사용자만 필터링
            List<String> valid_mentions = mentioned_users.stream()
                    .filter(server_members::contains)
                    .filter(user_id -> !user_id.equals(sender_id)) // 자기 자신은 제외
                    .toList();

            if (valid_mentions.isEmpty()) {
                log.debug("유효한 멘션 대상 없음: 메시지 ID {} | 원본 멘션: {}",
                        message.getMessageId(), mentioned_users);
                return;
            }

            // 각 멘션된 사용자에게 토스트 알림 전송
            for (String mentioned_user_id : valid_mentions) {
                send_mention_toast_notification(mentioned_user_id, message, sender_data);

                log.info("멘션 알림 전송 성공 대상자 : {}", mentioned_user_id);
            }

            log.info("멘션 알림 전송 완료: {}명에게 전송 | 메시지 ID {} | 전송자: {}",
                    valid_mentions.size(), message.getMessageId(), sender_id);

        } catch (Exception e) {
            log.error("멘션 알림 전송 실패: 메시지 ID {} | 오류: {}",
                    message.getMessageId(), e.getMessage(), e);
        }
    }

    /*
     * 메시지 내용에서 태그된 사용자 ID 목록을 추출
     */
    private List<String> extract_mentioned_users(String content) {
        List<String> mentioned_users = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            return mentioned_users;
        }

        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            String user_id = matcher.group(1);
            if (!mentioned_users.contains(user_id)) {
                mentioned_users.add(user_id);
            }
        }

        log.trace("메시지에서 멘션 추출 완료: {} | 추출된 사용자: {}", content, mentioned_users);
        return mentioned_users;
    }

    /*
     * 개별 사용자에게 멘션 토스트 알림 전송
     */
    protected void send_mention_toast_notification(String mentioned_user_id, Chat_messages message, users_dto sender_data) {
        try {
            CopyOnWriteArrayList<SseEmitter> userEmitters = userSubscribers.get(mentioned_user_id);
            if (userEmitters != null && !userEmitters.isEmpty()) {
                try {
                    Map<String, Object> xxx = new HashMap<>();
                    xxx.put("type", "MENTION_NOTIFICATION");
                    xxx.put("serverId", message.getChatChannel().getServerId());
                    xxx.put("channelId", message.getChatChannel().getChannelId());
                    xxx.put("messageId", message.getMessageId());
                    xxx.put("senderId", message.getUser().getUserId());
                    xxx.put("content", truncate_content(message.getContent(), 100));
                    xxx.put("senderData", sender_data);
                    xxx.put("timestamp", LocalDateTime.now());

                    String jsonMessage = objectMapper.writeValueAsString(xxx);

                    sendToUserEmitters(userEmitters, "MENTION_NOTIFICATION", jsonMessage, mentioned_user_id);
                } catch (Exception userEx) {
                    log.info("사용자 {}는 접속중이지 않습니다.", mentioned_user_id);
                }
            }

            log.debug("멘션 토스트 알림 전송: {} -> {} | 메시지 ID {}",
                    message.getUser().getUserId(), mentioned_user_id, message.getMessageId());

        } catch (Exception e) {
            log.error("멘션 토스트 알림 전송 실패: {} -> {} | 메시지 ID {} | 오류: {}",
                    message.getUser().getUserName(), mentioned_user_id, message.getMessageId(), e.getMessage(), e);
        }
    }

    /*
     * 메시지 내용 truncate (토스트 알림용)
     */
    private String truncate_content(String content, int max_length) {
        if (content == null) {
            return "";
        }

        if (content.length() <= max_length) {
            return content;
        }

        return content.substring(0, max_length) + "...";
    }
}