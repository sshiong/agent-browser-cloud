package io.browsercloud.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BrowserPlacementJpaRepository
    extends JpaRepository<BrowserPlacementEntity, String> {

  List<BrowserPlacementEntity> findAllByNodeIdAndStateIn(String nodeId, Collection<String> states);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select placement from BrowserPlacementEntity placement where placement.sessionId = :sessionId")
  Optional<BrowserPlacementEntity> findForUpdate(@Param("sessionId") String sessionId);
}
