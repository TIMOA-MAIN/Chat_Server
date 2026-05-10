package TIMOA.chatserver.service;

import TIMOA.chatserver.entity.Server_members;
import TIMOA.chatserver.repository.Server_member_repository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class Server_member_service {

    @Autowired
    private Server_member_repository server_member_repository;

    /*
     * 사용자가 특정 서버의 멤버인지 확인
     */
    public boolean check_user_of_server_member(String user_id, Long server_id) {
        try {
            log.debug("서버 멤버십 확인: userId={}, serverId={}", user_id, server_id);

            Optional<Server_members> member = server_member_repository
                    .findByUserIdAndServerId(user_id, server_id);

            boolean result = member.isPresent() && member.get().getIsActive();

            log.debug("서버 멤버십 확인 결과: userId={}, serverId={}, isMember={}, isActive={}",
                    user_id, server_id, member.isPresent(),
                    member.map(Server_members::getIsActive).orElse(false));

            return result;

        } catch (Exception e) {
            log.error("서버 멤버십 확인 중 오류 발생: userId={}, serverId={}, error={}",
                    user_id, server_id, e.getMessage());
            return false;
        }
    }

    /*
     * 사용자가 속한 서버 목록 조회
     */
    public List<Long> get_user_servers(String user_id) {
        try {
            log.debug("사용자 소속 서버 목록 조회: userId={}", user_id);

            List<Long> server_ids = new ArrayList<>();
            List<Server_members> servers = server_member_repository
                    .findByUserIdAndIsActive(user_id, true);

            for(Server_members s : servers) {
                server_ids.add(s.getServerId());
            }

            log.info("사용자 소속 서버 목록 조회 성공: userId={}, activeServerCount={}",
                    user_id, servers.size());

            if(log.isDebugEnabled()) {
                log.debug("사용자 소속 서버 IDs: userId={}, serverIds={}", user_id, server_ids);
            }

            return server_ids;

        } catch (Exception e) {
            log.error("사용자 소속 서버 목록 조회 중 오류 발생: userId={}, error={}",
                    user_id, e.getMessage());
            return List.of();
        }
    }

}