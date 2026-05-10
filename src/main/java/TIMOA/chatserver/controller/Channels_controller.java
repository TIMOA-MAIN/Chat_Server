package TIMOA.chatserver.controller;

import TIMOA.chatserver.DTO.chat_channels_dto;
import TIMOA.chatserver.DTO.update_channel_dto;
import TIMOA.chatserver.entity.Chat_channels;
import TIMOA.chatserver.service.Chat_channel_service;
import TIMOA.chatserver.service.Sse_service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/chat/channels")
public class Channels_controller {

    @Autowired
    private Chat_channel_service chat_channels_service;

    @Autowired
    private Sse_service sseService;

    /*
     * 채널 생성
     */
    @PostMapping("/create/{userId}")
    public ResponseEntity<?> create_channel(@RequestBody Chat_channels channel, @PathVariable String userId) {
        log.info("API 호출: POST /api/chat/channels/create/{}, channelName={}, serverId={}",
                userId, channel.getChannelName(), channel.getServerId());

        try {
            chat_channels_dto create_channel = chat_channels_service.create_channel(channel, userId);

            log.info("API 응답: POST /api/chat/channels/create/{}, channelId={}, channelName={}, serverId={}, status=SUCCESS",
                    userId, create_channel.getChannelId(), create_channel.getChannelName(), create_channel.getServerId());

            sseService.broadcastToServerMembers(create_channel.getServerId(), "CHANNEL_CREATE", "서버의 채널이 생성 되었습니다.");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "채널이 성공적으로 생성되었습니다.",
                    "channel", create_channel
            ));

        } catch (Exception e) {
            log.error("API 오류: POST /api/chat/channels/create/{}, channelName={}, serverId={}, error={}",
                    userId, channel.getChannelName(), channel.getServerId(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /*
     * 채널 수정
     */
    @PutMapping("/update/{channelId}")
    public ResponseEntity<?> update_channel(@PathVariable Long channelId,
                                            @RequestBody update_channel_dto request) {
        log.info("API 호출: PUT /api/chat/channels/update/{}, userId={}, newChannelName={}",
                channelId, request.getUserId(), request.getChannelName());

        try {
            chat_channels_dto updated_channel = chat_channels_service.update_channel(
                    channelId,
                    request.getUserId(),
                    request.getChannelName()
            );

            log.info("API 응답: PUT /api/chat/channels/update/{}, userId={}, newChannelName={}, status=SUCCESS",
                    channelId, request.getUserId(), request.getChannelName());

            sseService.broadcastToServerMembers(updated_channel.getServerId(), "CHANNEL_UPDATE", "서버의 채널이 수정 되었습니다.");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "채널이 성공적으로 수정되었습니다.",
                    "channel", updated_channel
            ));

        } catch (Exception e) {
            log.error("API 오류: PUT /api/chat/channels/update/{}, userId={}, newChannelName={}, error={}",
                    channelId, request.getUserId(), request.getChannelName(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /*
     * 채널 삭제
     */
    @DeleteMapping("/delete/{channelId}")
    public ResponseEntity<?> delete_channel(@PathVariable Long channelId,
                                            @RequestParam String userId) {
        log.info("API 호출: DELETE /api/chat/channels/delete/{}, userId={}", channelId, userId);

        try {
            Long server_id = chat_channels_service.delete_channel(channelId, userId);

            log.info("API 응답: DELETE /api/chat/channels/delete/{}, userId={}, status=SUCCESS",
                    channelId, userId);

            sseService.broadcastToServerMembers(server_id, "CHANNEL_DELETE", "서버의 채널이 삭제 되었습니다.");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "채널이 성공적으로 삭제되었습니다."
            ));

        } catch (Exception e) {
            log.error("API 오류: DELETE /api/chat/channels/delete/{}, userId={}, error={}",
                    channelId, userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /*
     * 사용자 접근 가능 채팅 채널 목록 조회
     */
    @GetMapping("/server/{serverId}/user/{userId}")
    public ResponseEntity<?> get_user_accessible_chat_channels(@PathVariable Long serverId,
                                                               @PathVariable String userId) {
        log.info("API 호출: GET /api/chat/channels/server/{}/user/{}", serverId, userId);

        try {
            List<chat_channels_dto> channels = chat_channels_service.get_user_accessible_channels(userId, serverId);

            if(channels==null) {
                log.warn("API 경고: GET /api/chat/channels/server/{}/user/{}, 접근 가능한 채널 없음",
                        serverId, userId);
                throw new Exception("참여 가능 채널이 없습니다.");
            }

            log.info("API 응답: GET /api/chat/channels/server/{}/user/{}, channelCount={}, status=SUCCESS",
                    serverId, userId, channels.size());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "channels", channels
            ));

        } catch (Exception e) {
            log.error("API 오류: GET /api/chat/channels/server/{}/user/{}, error={}",
                    serverId, userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }
}