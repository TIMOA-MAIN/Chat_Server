package TIMOA.chatserver.controller;

import TIMOA.chatserver.DTO.*;
import TIMOA.chatserver.entity.Chat_channels;
import TIMOA.chatserver.entity.Chat_messages;
import TIMOA.chatserver.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;


@Slf4j
@RestController
@RequestMapping("/api/chats")
public class Chat_controller {

    @Autowired
    private Chat_service chat_service;

    @Autowired
    private Sse_service sse_service;

    @Autowired
    private Chat_channel_service chat_channel_service;

    @Autowired
    private Server_member_service server_member_service;

    /*
     * 메시지 전송
     */
    @PostMapping("/send")
    public void handle_message_send(@RequestBody chat_message_dto request) {
        try {
            log.info("채팅 메세지 전송 응답 받음 {}", request);

            chat_message_response_dto save_message = chat_service.save_text_message(request);
            if(save_message == null) {
                throw new Exception("채팅 메시지 생성 실패");
            }

            sse_service.broadcastToChannelMembers(request.getServerId(), request.getChannelId(), "MESSAGE_CREATE", save_message);

            log.info("메시지 전송 완료: 사용자={} | 메시지 ID={} | 서버={} | 채널={}",
                    request.getUserId(), save_message.getMessageId(), request.getServerId(), request.getChannelId());

        } catch (Exception e) {
            log.error("메시지 전송 실패: 사용자={} | 서버={} | 채널={} | 오류: {}",
                    request.getUserId(), request.getServerId(),
                    request.getChannelId(), e.getMessage(), e);
            sendErrorToUser(request.getUserId(), e.getMessage());
        }
    }

    /*
     * 메시지 수정
     */
    @PostMapping("/update")
    public void handle_message_update(@RequestBody chat_update_dto request) {
        try {
            log.debug("메시지 수정 요청: 사용자={} | 메시지 ID={}", request.getOrderId(), request.getMessageId());

            // 메시지 수정
            chat_message_request_dto message = chat_service.update_message(request);

            sse_service.broadcastToChannelMembers(request.getServerId(), request.getChannelId(), "MESSAGE_UPDATE", message);

            log.info("메시지 수정 완료: 메시지 ID={} | 삭제자={} | 서버={} | 채널={}",
                    request.getMessageId(), request.getSenderId(), message.getServerId(),
                    message.getChannelId());

        } catch (Exception e) {
            log.error("메시지 수정 실패: 사용자={} | 메시지 ID={} | 오류: {}",
                    request.getSenderId(), request.getMessageId(), e.getMessage(), e);
            sendErrorToUser(request.getSenderId(), e.getMessage());
        }
    }

    /*
     * 메시지 삭제
     */
    @DeleteMapping("/delete")
    public void handle_message_delete(@RequestBody chat_update_dto request) {
        try {
            log.debug("메시지 삭제 요청: 사용자={} | 메시지 ID={}", request.getOrderId(), request.getMessageId());

            // 메시지 삭제
            chat_message_request_dto message = chat_service.delete_message(request.getMessageId(), request.getOrderId());

            sse_service.broadcastToChannelMembers(request.getServerId(), request.getChannelId(), "MESSAGE_DELETE", message);

            log.info("메시지 삭제 완료: 메시지 ID={} | 삭제자={} | 서버={} | 채널={}",
                    request.getMessageId(), request.getSenderId(), message.getServerId(),
                    message.getChannelId());

        } catch (Exception e) {
            log.error("메시지 삭제 실패: 사용자={} | 메시지 ID={} | 오류: {}",
                    request.getSenderId(), request.getMessageId(), e.getMessage(), e);
            sendErrorToUser(request.getSenderId(), e.getMessage());
        }
    }


    /*
     * 파일 업로드
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload_file(@RequestParam Long server_id,
                                         @RequestParam Long channel_id,
                                         @RequestParam String sender_id,
                                         @RequestParam String message_type,
                                         @RequestParam MultipartFile file){

        log.info("API 호출: POST /api/chats/upload, serverId={}, channelId={}, senderId={}, messageType={}, fileName={}, fileSize={}",
                server_id, channel_id, sender_id, message_type, file.getOriginalFilename(), file.getSize());

        try{
            // 파일 크기 검증
            if(file.getSize() > 50 * 1024 * 1024) { // 50MB 제한
                log.warn("파일 업로드 실패 - 크기 초과: fileName={}, fileSize={}, maxSize=50MB",
                        file.getOriginalFilename(), file.getSize());
                throw new Exception("파일 크기가 50MB를 초과합니다.");
            }

            // 채널 존재 확인
            Chat_channels find_channel = chat_channel_service.get_channel_by_id(server_id, channel_id);
            if(find_channel==null) {
                log.warn("파일 업로드 실패 - 존재하지 않는 채널: serverId={}, channelId={}", server_id, channel_id);
                throw new Exception("없는 채널 아이디 입니다.");
            }

            Chat_messages message = new Chat_messages();
            message.setChatChannel(find_channel);
            message.setSenderId(sender_id);
            message.setMessageType(message_type);
            message.setFileName(file.getOriginalFilename());
            message.setFileSize(file.getSize());

            chat_message_response_dto saved_message = chat_service.save_file_message(file, message);

            if(saved_message==null) {
                log.error("파일 업로드 실패 - 메시지 저장 실패: fileName={}, senderId={}",
                        file.getOriginalFilename(), sender_id);
                throw new Exception("파일 업로드 실패");
            }

            sse_service.broadcastToChannelMembers(server_id, channel_id, "MESSAGE_CREATE", saved_message);

            log.info("파일 업로드 성공: messageId={}, fileName={}, fileSize={}, senderId={}, serverId={}, channelId={}",
                    saved_message.getMessageId(), file.getOriginalFilename(), file.getSize(),
                    sender_id, server_id, channel_id);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "파일 업로드 성공",
                    "filedata" , saved_message
            ));
        } catch (Exception e) {
            log.error("API 오류: POST /api/chats/upload, serverId={}, channelId={}, senderId={}, fileName={}, error={}",
                    server_id, channel_id, sender_id, file.getOriginalFilename(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /*
       파일 다운로드
     */
    @GetMapping("/download")
    public ResponseEntity<?> download_file(
            @RequestParam String filename,
            @RequestParam(required = false) Long messageId
    ){
        log.info("API 호출: GET /api/chats/download, fileName={}", filename);

        try{
            // 파일명 검증
            if(filename == null || filename.trim().isEmpty()) {
                log.warn("파일 다운로드 실패 - 잘못된 파일명: fileName={}", filename);
                throw new Exception("파일명이 유효하지 않습니다.");
            }

            Resource resource = chat_service.download_file(filename, messageId);
            if(resource == null || !resource.exists()) {
                log.warn("파일 다운로드 실패 - 파일 없음: fileName={}", filename);
                throw new Exception("파일을 찾을 수 없습니다.");
            }

            String content_type = chat_service.get_content_type(filename);
            String encoded_filename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

            log.info("파일 다운로드 성공: fileName={}, contentType={}, fileSize={}",
                    filename, content_type, resource.contentLength());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(content_type))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encoded_filename)
                    .body(resource);
        } catch (Exception e) {
            log.error("API 오류: GET /api/chats/download, fileName={}, error={}", filename, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /*
     * 채널 입장 시 최신 메시지 로드
     */
    @GetMapping("/channels/{serverId}/{channelId}/messages")
    public ResponseEntity<chat_history_dto> get_recent_messages(
            @PathVariable Long serverId,
            @PathVariable Long channelId,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam String userId) {

        log.info("API 호출: GET /api/chats/channels/{}/{}/messages, userId={}, size={}",
                serverId, channelId, userId, size);

        try {
            // 파라미터 검증
            if(size > 100) {
                log.warn("메시지 로드 실패 - 크기 제한 초과: userId={}, size={}, maxSize=100", userId, size);
                size = 100; // 최대 100개로 제한
            }

            // 사용자 권한 확인
            if (!server_member_service.check_user_of_server_member(userId, serverId)) {
                log.warn("메시지 로드 실패 - 권한 없음: userId={}, serverId={}", userId, serverId);
                return ResponseEntity.status(403).build();
            }

            // 채널 존재 확인
            Chat_channels channel = chat_channel_service.get_channel_by_id(serverId, channelId);
            if (channel == null) {
                log.warn("메시지 로드 실패 - 존재하지 않는 채널: serverId={}, channelId={}", serverId, channelId);
                return ResponseEntity.status(404).build();
            }

            chat_history_dto response = chat_service.get_recent_messages(channelId, size);

            log.info("최신 메시지 로드 성공: userId={}, serverId={}, channelId={}, messageCount={}, hasMore={}",
                    userId, serverId, channelId, response.getMessages().size(), response.isHasMore());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("API 오류: GET /api/chats/channels/{}/{}/messages, userId={}, error={}",
                    serverId, channelId, userId, e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    /*
     * 이전 메시지 더 로드하기
     */
    @GetMapping("/channels/{serverId}/{channelId}/messages/before")
    public ResponseEntity<chat_history_dto> get_messages_before(
            @PathVariable Long serverId,
            @PathVariable Long channelId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime before,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam String userId) {

        log.info("API 호출: GET /api/chats/channels/{}/{}/messages/before, userId={}, before={}, size={}",
                serverId, channelId, userId, before, size);

        try {
            // 파라미터 검증
            if(size > 100) {
                log.warn("이전 메시지 로드 실패 - 크기 제한 초과: userId={}, size={}, maxSize=100", userId, size);
                size = 100;
            }

            if(before.isAfter(LocalDateTime.now())) {
                log.warn("이전 메시지 로드 실패 - 잘못된 시간: userId={}, before={}", userId, before);
                return ResponseEntity.badRequest().build();
            }

            // 사용자 권한 확인
            if (!server_member_service.check_user_of_server_member(userId, serverId)) {
                log.warn("이전 메시지 로드 실패 - 권한 없음: userId={}, serverId={}", userId, serverId);
                return ResponseEntity.status(403).build();
            }

            // 채널 존재 확인
            Chat_channels channel = chat_channel_service.get_channel_by_id(serverId, channelId);
            if (channel == null) {
                log.warn("이전 메시지 로드 실패 - 존재하지 않는 채널: serverId={}, channelId={}", serverId, channelId);
                return ResponseEntity.status(404).build();
            }

            chat_history_dto response = chat_service.get_messages_before(channelId, before, size);

            log.info("이전 메시지 로드 성공: userId={}, serverId={}, channelId={}, before={}, messageCount={}, hasMore={}",
                    userId, serverId, channelId, before, response.getMessages().size(), response.isHasMore());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("API 오류: GET /api/chats/channels/{}/{}/messages/before, userId={}, before={}, error={}",
                    serverId, channelId, userId, before, e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }


    /*
     * 메시지 전송 실패 시 사용자에게 오류 메시지 전송
     */
    private void sendErrorToUser(String user_id, String error_message) {
        if (user_id != null) {
            log.debug("사용자에게 오류 메시지 전송: {} | 메시지: {}", user_id, error_message);
            chat_event_dto error_event = chat_event_dto.builder()
                    .type("ERROR")
                    .data(Map.of("message", error_message))
                    .timestamp(LocalDateTime.now())
                    .build();

            sse_service.unicateToUserErrorMessage(user_id, "MESSAGE_ERROR", "메시지 전송 실패");
        } else {
            log.warn("사용자 ID가 null이어서 오류 메시지를 전송할 수 없음: {}", error_message);
        }
    }
}