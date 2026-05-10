package TIMOA.chatserver.repository;

import TIMOA.chatserver.entity.Servers;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface Servers_repository extends JpaRepository<Servers, Long> {
}