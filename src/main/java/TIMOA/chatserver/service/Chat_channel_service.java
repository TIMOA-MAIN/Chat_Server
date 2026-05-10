package TIMOA.chatserver.service;

import TIMOA.chatserver.DTO.chat_channels_dto;
import TIMOA.chatserver.entity.Chat_channels;
import TIMOA.chatserver.entity.Chat_messages;
import TIMOA.chatserver.entity.Servers;
import TIMOA.chatserver.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class Chat_channel_service {

    @Autowired
    private Chat_channels_repository chat_channels_repository;

    @Autowired
    private Server_member_repository server_member_repository;

    @Autowired
    private Servers_repository servers_repository;

    @Autowired
    private Chat_repository chat_repository;

    @Autowired
    private S3FileRepository S3;

    /*
     * 채널 검색 아이디
     */
    public Chat_channels get_channel_by_id(Long server_id, Long channel_id){
        try {
            log.debug("채널 조회: serverId={}, channelId={}", server_id, channel_id);

            if(chat_channels_repository.findByServerIdAndChannelId(server_id, channel_id).isEmpty()) {
                log.debug("채널 조회 실패 - 존재하지 않는 채널: serverId={}, channelId={}", server_id, channel_id);
                return null;
            }

            Chat_channels channel = chat_channels_repository.findByServerIdAndChannelId(server_id, channel_id).get();
            log.debug("채널 조회 성공: serverId={}, channelId={}, channelName={}",
                    server_id, channel_id, channel.getChannelName());

            return channel;
        } catch (Exception e) {
            log.error("채널 조회 중 오류 발생: serverId={}, channelId={}, error={}",
                    server_id, channel_id, e.getMessage());
            return null;
        }
    }

    /*
     * 채널 생성
     */
    public chat_channels_dto create_channel(Chat_channels channels, String user_id) {
        try {
            log.info("채널 생성 시도: channelName={}, serverId={}, userId={}",
                    channels.getChannelName(), channels.getServerId(), user_id);

            Servers server = servers_repository.findById(channels.getServerId())
                    .orElseThrow(() -> new Exception("서버를 찾을 수 없습니다."));

            if (server_member_repository.findByUserIdAndServerId(user_id, channels.getServerId()).isEmpty()) {
                log.warn("채널 생성 실패 - 서버 멤버가 아님: serverId={}, userId={}", channels.getServerId(), user_id);
                throw new Exception("해당 서버의 멤버가 아닙니다.");
            }

            // 채널 생성
            Chat_channels channel = new Chat_channels();
            channel.setChannelName(channels.getChannelName());
            channel.setServer(server);

            Chat_channels saved_channel = chat_channels_repository.save(channel);

            Long channel_count = chat_channels_repository.countByServerId(channels.getServerId());
            log.info("채널 생성 성공: channelId={}, channelName={}, serverId={}, serverName={}, totalChannels={}",
                    saved_channel.getChannelId(), saved_channel.getChannelName(),
                    server.getServerId(), server.getServerName(), channel_count);

            return chat_channels_dto.fromEntity(saved_channel);

        } catch (Exception e) {
            log.error("채널 생성 중 오류 발생: channelName={}, serverId={}, userId={}, error={}",
                    channels.getChannelName(), channels.getServerId(), user_id, e.getMessage());
            throw new RuntimeException("채널 생성에 실패했습니다: " + e.getMessage());
        }
    }

    /*
     * 채널 이름 수정
     */
    public chat_channels_dto update_channel(Long channel_id, String user_id, String new_channel_name){
        try {
            log.info("채널 수정 시도: channelId={}, userId={}, newChannelName={}",
                    channel_id, user_id, new_channel_name);

            Chat_channels channel = chat_channels_repository.findById(channel_id)
                    .orElseThrow(() -> new Exception("채널을 찾을 수 없습니다."));

            if (server_member_repository.findByUserIdAndServerId(user_id, channel.getServerId()).isEmpty()) {
                log.warn("채널 수정 실패 - 서버 멤버가 아님: channelId={}, serverId={}, userId={}",
                        channel_id, channel.getServerId(), user_id);
                throw new Exception("해당 서버의 멤버가 아닙니다.");
            }

            String old_channel_name = channel.getChannelName();
            channel.setChannelName(new_channel_name);
            Chat_channels updated_channel = chat_channels_repository.save(channel);

            log.info("채널 수정 성공: channelId={}, oldName={}, newName={}, serverId={}, userId={}",
                    channel_id, old_channel_name, new_channel_name, channel.getServerId(), user_id);

            return chat_channels_dto.fromEntity(updated_channel);

        } catch (Exception e) {
            log.error("채널 수정 중 오류 발생: channelId={}, userId={}, newChannelName={}, error={}",
                    channel_id, user_id, new_channel_name, e.getMessage());
            throw new RuntimeException("채널 수정에 실패했습니다: " + e.getMessage());
        }
    }

    /*
     * 채널 삭제
     */
    public Long delete_channel(Long channel_id, String user_id) {
        try {
            log.info("채널 삭제 시도: channelId={}, userId={}", channel_id, user_id);

            Chat_channels channel = chat_channels_repository.findById(channel_id)
                    .orElseThrow(() -> new IllegalArgumentException("채널을 찾을 수 없습니다."));

            // 사용자의 서버 멤버십 확인
            if (server_member_repository.findByUserIdAndServerId(user_id, channel.getServerId()).isEmpty()) {
                log.warn("채널 삭제 실패 - 서버 멤버가 아님: channelId={}, serverId={}, userId={}",
                        channel_id, channel.getServerId(), user_id);
                throw new IllegalArgumentException("해당 서버의 멤버가 아닙니다.");
            }

            String channel_name = channel.getChannelName();
            Long server_id = channel.getServerId();

            List<Chat_messages> messages = chat_repository.findByChannelId(channel.getChannelId());
            for(Chat_messages m : messages) {
                if(m.getMessageType().equals("FILE")){
                    S3.deleteFile(m.getFileUrl());
                }
            }

            chat_channels_repository.delete(channel);

            Long remaining_channels = chat_channels_repository.countByServerId(server_id);
            log.info("채널 삭제 성공: channelId={}, channelName={}, serverId={}, remainingChannels={}",
                    channel_id, channel_name, server_id, remaining_channels);

            return server_id;
        } catch (Exception e) {
            log.error("채널 삭제 중 오류 발생: channelId={}, userId={}, error={}",
                    channel_id, user_id, e.getMessage());
            throw new RuntimeException("채널 삭제에 실패했습니다: " + e.getMessage());
        }
    }

    /*
     * 사용자가 접근 가능한 채팅 채널 목록 조회
     */
    public List<chat_channels_dto> get_user_accessible_channels(String user_id, Long server_id) {
        try {
            log.debug("사용자 접근 가능 채널 조회: userId={}, serverId={}", user_id, server_id);

            // 사용자의 서버 멤버십 확인
            if (server_member_repository.findByUserIdAndServerId(user_id, server_id).isEmpty()) {
                log.warn("채널 목록 조회 실패 - 서버 멤버가 아님: userId={}, serverId={}", user_id, server_id);
                throw new IllegalArgumentException("해당 서버의 멤버가 아닙니다.");
            }

            List<Chat_channels> channels = chat_channels_repository.findByServerId(server_id);

            List<chat_channels_dto> channels_dto = new ArrayList<>();
            for(Chat_channels c : channels){
                channels_dto.add(chat_channels_dto.fromEntity(c));
            }

            log.info("사용자 접근 가능 채널 조회 성공: userId={}, serverId={}, channelCount={}",
                    user_id, server_id, channels.size());

            // 각 채널 정보 디버그 로깅
            if(log.isDebugEnabled()) {
                for(Chat_channels channel : channels) {
                    log.debug("접근 가능 채널: channelId={}, channelName={}",
                            channel.getChannelId(), channel.getChannelName());
                }
            }

            return channels_dto;

        } catch (Exception e) {
            log.error("사용자 접근 가능 채널 조회 중 오류 발생: userId={}, serverId={}, error={}",
                    user_id, server_id, e.getMessage());
            return List.of();
        }
    }



}