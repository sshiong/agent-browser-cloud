package io.browsercloud.infrastructure;

import io.browsercloud.persistence.BrowserStateEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BrowserStateJpaRepository extends JpaRepository<BrowserStateEntity, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select state from BrowserStateEntity state where state.sessionId = :sessionId")
  Optional<BrowserStateEntity> findByIdForUpdate(@Param("sessionId") String sessionId);
}
