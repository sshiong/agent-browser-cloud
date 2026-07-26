package io.browsercloud.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecureDebugAccessEventJpaRepository
    extends JpaRepository<SecureDebugAccessEventEntity, String> {

  List<SecureDebugAccessEventEntity> findAllByDebugSessionIdOrderBySequenceNoAsc(
      String debugSessionId);
}
