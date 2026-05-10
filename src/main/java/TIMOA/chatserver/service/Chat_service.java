package TIMOA.chatserver.service;

import TIMOA.chatserver.DTO.*;
import TIMOA.chatserver.entity.Chat_channels;
import TIMOA.chatserver.entity.Chat_messages;
import TIMOA.chatserver.entity.Users;
import TIMOA.chatserver.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Transactional(rollbackFor = Exception.class)
@Service
public class Chat_service {

    @Autowired
    private Chat_repository cr;

    @Autowired
    private File_management_repository fmr;

    @Autowired
    private Server_member_repository smr;

    @Autowired
    private Chat_channels_repository ccr;

    @Autowired
    private User_repository user;

    @Autowired
    private S3FileRepository s3file;


    /*
    텍스트 메세지 저장
     */
    public chat_message_response_dto save_text_message(chat_message_dto request){
        try{
            log.debug("메시지 전송 요청: 사용자={} | 서버={} | 채널={} | 길이={}자",
                    request.getUserId(), request.getServerId(), request.getChannelId(),
                    request.getContent() != null ? request.getContent().length() : 0);

            Users find_user = user.findById(request.getUserId()).orElseThrow(() -> new Exception("없는 유저 아이디 입니다."));

            smr.findByUserIdAndServerId(request.getUserId(), request.getServerId())
                    .orElseThrow(() -> new Exception("해당 서버에 접근 권한이 없습니다."));

            Chat_channels find_channel = ccr.findByServerIdAndChannelId(request.getServerId(), request.getChannelId())
                    .orElseThrow(() -> new Exception("없는 채널 아이디 입니다."));

            // 메시지 저장
            Chat_messages message = new Chat_messages();
            message.setChatChannel(find_channel);
            message.setUser(find_user);
            message.setMessageType("TEXT");
            message.setContent(request.getContent());
            log.debug("텍스트 메시지 저장 시작: 전송자 {} | 채널 {} | 길이 {}",
                    message.getSenderId(), message.getChatChannel().getChannelId(),
                    message.getContent() != null ? message.getContent().length() : 0);

            Chat_messages save_message = cr.save(message);

            log.info("텍스트 메시지 저장 성공: ID {} | 전송자 {} | 채널 {}",
                    save_message.getMessageId(), message.getUser().getUserName(),
                    message.getChatChannel().getChannelId());

            return chat_message_response_dto.fromEntity(save_message, save_message.getUser().getNickName(), null);
        } catch (Exception e) {
            log.error("텍스트 메시지 저장 실패: 전송자 {} | 채널 {} | 오류: {}",
                    request.getUserId(),
                    request.getChannelId(),
                    e.getMessage(), e);
            return null;
        }
    }

    /*
    파일 메세지 및 데이터 저장
    파일 원본 : 로컬
    파일 데이터 : DB
     */
    @Transactional(rollbackFor = Exception.class)
    public chat_message_response_dto save_file_message(MultipartFile file, Chat_messages message){
        try{
            log.debug("파일 메시지 저장 시작: 파일명 {} | 크기 {} bytes | 전송자 {}",
                    file.getOriginalFilename(), file.getSize(), message.getSenderId());

            Users find_user = user.findById(message.getSenderId()).orElseThrow(() -> new Exception("없는 유저 아이디 입니다."));

            smr.findByUserIdAndServerId(message.getSenderId(), message.getChatChannel().getServerId())
                    .orElseThrow(() -> new Exception("해당 서버에 접근 권한이 없습니다."));

            String uniqueFileName = s3file.uploadFile(file);
            log.debug("파일 업로드 완료: {}", uniqueFileName);

            message.setFileUrl(uniqueFileName);
            message.setFileName(file.getOriginalFilename());
            message.setUser(find_user);

            Chat_messages saved_message = cr.save(message);

            log.info("파일 메시지 저장 성공: ID {} | 파일명 {} | 전송자 {} | 채널 {}",
                    saved_message.getMessageId(), saved_message.getFileName(),
                    saved_message.getSenderId(), saved_message.getChatChannel().getChannelId());

            return chat_message_response_dto.fromEntity(saved_message, saved_message.getUser().getNickName(), null);
        } catch (Exception e) {
            log.error("파일 메시지 저장 실패: 파일명 {} | 전송자 {} | 오류: {}",
                    file.getOriginalFilename(), message.getSenderId(), e.getMessage(), e);
            return null;
        }
    }

    /*
     * 메시지 삭제
     */
    public chat_message_request_dto update_message(chat_update_dto new_message) {
        try {
            log.debug("메시지 수정 요청: 사용자 {} | 수정 내용 {}", new_message.getOrderId(), new_message.getContent());

            Chat_messages message = cr.findById(new_message.getMessageId())
                    .orElseThrow(() -> new IllegalArgumentException("메시지를 찾을 수 없습니다: " + new_message.getMessageId()));

            // 메시지 작성자 확인
            if ("[삭제된 사용자]".equals(message.getSenderId())) {
                log.warn("익명화된 메시지 수정 시도: 요청자 {} | 메시지 ID {}", new_message, new_message.getMessageId());
                throw new IllegalArgumentException("삭제된 사용자의 메시지는 수정할 수 없습니다.");
            }

            // 메시지 작성자 확인
            if (!message.getSenderId().equals(new_message.getOrderId())) {
                log.warn("메시지 수정 권한 없음: 요청자 {} | 작성자 {} | 메시지 ID {}",
                        new_message.getOrderId(), message.getSenderId(), new_message.getMessageId());
                throw new IllegalArgumentException("본인이 작성한 메시지만 수정할 수 있습니다.");
            }

            message.setContent(new_message.getContent());

            // 메시지 삭제
            cr.save(message);

            log.info("메시지 수정 성공: ID {} | 사용자 {} | 채널 {} | 수정 내용: {}",
                    new_message.getMessageId(), new_message.getOrderId(), message.getChatChannel().getChannelId(), new_message.getContent());

            return chat_message_request_dto.fromEntity(message);
        } catch (Exception e) {
            log.error("메시지 수정 실패: ID {} | 요청자 {} | 오류: {}",
                    new_message.getMessageId(), new_message.getOrderId(), e.getMessage(), e);
            throw new RuntimeException("메시지 삭제에 실패했습니다: " + e.getMessage());
        }
    }

    /*
     * 메시지 삭제
     */
    public chat_message_request_dto delete_message(Long message_id, String user_id) {
        try {
            log.debug("메시지 삭제 요청: ID {} | 요청자 {}", message_id, user_id);

            Chat_messages message = cr.findById(message_id)
                    .orElseThrow(() -> new IllegalArgumentException("메시지를 찾을 수 없습니다: " + message_id));

            // 서버 멤버 확인
            if (smr.findByUserIdAndServerId(user_id, message.getChatChannel().getServerId()).isEmpty()) {
                log.warn("서버 접근 권한 없음: 사용자 {} | 서버 {} | 메시지 ID {}",
                        user_id, message.getChatChannel().getServerId(), message_id);
                throw new IllegalArgumentException("해당 서버에 접근 권한이 없습니다.");
            }

            // 메시지 작성자 확인
            if ("[삭제된 사용자]".equals(message.getSenderId())) {
                log.warn("익명화된 메시지 삭제 시도: 요청자 {} | 메시지 ID {}", user_id, message_id);
                throw new IllegalArgumentException("삭제된 사용자의 메시지는 삭제할 수 없습니다.");
            }

            // 메시지 작성자 확인
            if (!message.getSenderId().equals(user_id)) {
                log.warn("메시지 삭제 권한 없음: 요청자 {} | 작성자 {} | 메시지 ID {}",
                        user_id, message.getSenderId(), message_id);
                throw new IllegalArgumentException("본인이 작성한 메시지만 삭제할 수 있습니다.");
            }

            // 파일이 첨부된 메시지인 경우 파일도 삭제
            if (message.getFileName() != null && !message.getFileName().isEmpty()) {
                try {
                    //fmr.delete_file(message.getFileName());
                    s3file.deleteFile(message.getFileUrl());
                    log.debug("첨부파일 삭제 성공: {} | 메시지 ID {}", message.getFileName(), message_id);
                } catch (Exception e) {
                    log.warn("첨부파일 삭제 실패: {} | 메시지 ID {} | 오류: {}",
                            message.getFileName(), message_id, e.getMessage());
                }
            }

            // 메시지 삭제
            cr.delete(message);

            log.info("메시지 삭제 성공: ID {} | 작성자 {} | 채널 {} | 첨부파일: {}",
                    message_id, user_id, message.getChatChannel().getChannelId(),
                    message.getFileName() != null ? message.getFileName() : "없음");

            return chat_message_request_dto.fromEntity(message);

        } catch (Exception e) {
            log.error("메시지 삭제 실패: ID {} | 요청자 {} | 오류: {}",
                    message_id, user_id, e.getMessage(), e);
            throw new RuntimeException("메시지 삭제에 실패했습니다: " + e.getMessage());
        }
    }

    /*
    파일 다운로드
     */
    public Resource download_file(String file_name, Long messageId){
        try{
            log.info("파일 다운로드 요청: {}", file_name);

            Chat_messages findmessage = cr.findById(messageId)
                    .orElseThrow(() -> new Exception("메시지를 찾을 수 없습니다."));

            String uniqueFileName = findmessage.getFileUrl();

            Resource resource;
            try {
                resource = s3file.downloadFile(uniqueFileName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            if (resource != null && resource.exists()) {
                log.info("파일 다운로드 성공: {}", file_name);
            }

            return resource;
        } catch (Exception e) {
            log.error("파일 다운로드 실패: {} | 오류: {}", file_name, e.getMessage(), e);
            return null;
        }
    }

    public String get_content_type(String filename){
        try{
            log.trace("Content-Type 조회: {}", filename);
            String contentType = fmr.getContentType(filename);
            log.trace("Content-Type 결과: {} -> {}", filename, contentType);
            return contentType;
        } catch (Exception e) {
            log.debug("Content-Type 조회 실패, 기본값 사용: {} | 오류: {}", filename, e.getMessage());
            return "application/octet-stream";
        }
    }

    /*
     * 채널 입장 시 최신 메시지 로드 (기본 20개)
     */
    public chat_history_dto get_recent_messages(Long channel_id, int size) {
        try {
            log.debug("최신 메시지 로드 요청: 채널 {} | 개수 {}", channel_id, size);

            Pageable pageable = PageRequest.of(0, size);
            Page<Chat_messages> message_page = cr.findByChannelIdOrderBySentAtDesc(channel_id, pageable);

            List<Chat_messages> messages = new ArrayList<>(message_page.getContent());

            List<chat_message_response_dto> message_dtos = set_dto_data(messages);

            // 시간순으로 정렬 (오래된 것부터)
            Collections.reverse(messages);

            log.info("최신 메시지 로드 완료: 채널 {} | 로드된 메시지 {}개 | 추가 데이터 여부: {}",
                    channel_id, messages.size(), message_page.hasNext());

            return chat_history_dto.builder()
                    .messages(message_dtos)
                    .hasMore(message_page.hasNext())
                    .totalCount(message_page.getTotalElements())
                    .build();

        } catch (Exception e) {
            log.error("최신 메시지 로드 실패: 채널 {} | 오류: {}", channel_id, e.getMessage(), e);
            return chat_history_dto.builder()
                    .messages(Collections.emptyList())
                    .hasMore(false)
                    .totalCount(0L)
                    .build();
        }
    }

    /*
     * 이전 메시지 더 로드하기 (무한 스크롤)
     */
    public chat_history_dto get_messages_before(Long channel_id, LocalDateTime before_time, int size) {
        try {
            log.debug("이전 메시지 로드 요청: 채널 {} | 기준 시간 {} | 개수 {}",
                    channel_id, before_time, size);

            Pageable pageable = PageRequest.of(0, size);
            List<Chat_messages> messages = cr.findByChannelIdAndSentAtBeforeOrderBySentAtDesc(
                    channel_id, before_time, pageable);

            List<chat_message_response_dto> message_dtos = set_dto_data(messages);

            // 시간순으로 정렬 (오래된 것부터)
            Collections.reverse(messages);

            boolean has_more = messages.size() == size;

            log.info("이전 메시지 로드 완료: 채널 {} | 로드된 메시지 {}개 | 기준 시간 {} | 추가 데이터 여부: {}",
                    channel_id, message_dtos.size(), before_time, has_more);

            return chat_history_dto.builder()
                    .messages(message_dtos)
                    .hasMore(has_more)
                    .build();

        } catch (Exception e) {
            log.error("이전 메시지 로드 실패: 채널 {} | 기준 시간 {} | 오류: {}",
                    channel_id, before_time, e.getMessage(), e);
            return chat_history_dto.builder()
                    .messages(Collections.emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    private List<chat_message_response_dto> set_dto_data (List<Chat_messages> messages){
        List<chat_message_response_dto> message_dtos = new ArrayList<>();
        String user_nickname = null;
        try{
            for(Chat_messages message : messages) {
                String sender_id = message.getSenderId();
                user_nickname= user.findById(sender_id).orElseThrow(() -> new IllegalArgumentException("없는 sender_id 정보입니다: " + sender_id)).getNickName();

                if(message.getMessageType().equals("FILE")){
                    String image_file = s3file.downloadFileToString(message.getFileUrl());
                    message_dtos.add(chat_message_response_dto.fromEntity(message, user_nickname, image_file));
                }
                else{
                    message_dtos.add(chat_message_response_dto.fromEntity(message, user_nickname, null));
                }
            }

            return message_dtos;
        }catch (Exception e){
            throw  new RuntimeException(e);
        }
    }
}