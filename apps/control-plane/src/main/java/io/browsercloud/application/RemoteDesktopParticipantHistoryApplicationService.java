package io.browsercloud.application;

import static io.browsercloud.api.RemoteDesktopParticipantHistoryModels.*;

import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.persistence.RemoteDesktopParticipantHistoryQueryRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemoteDesktopParticipantHistoryApplicationService {
  private final RemoteDesktopParticipantHistoryQueryRepository history;
  private final SessionRepository sessions;
  private final Duration retention;
  private final int purgeBatchSize;

  public RemoteDesktopParticipantHistoryApplicationService(
      RemoteDesktopParticipantHistoryQueryRepository history,
      SessionRepository sessions,
      @Value("${remote-desktop.participant-history-retention-days:30}") long retentionDays,
      @Value("${remote-desktop.participant-history-purge-batch-size:10000}") int purgeBatchSize) {
    if (retentionDays < 7 || retentionDays > 365) {
      throw new IllegalStateException("Remote desktop participant retention must be 7 to 365 days");
    }
    if (purgeBatchSize < 1 || purgeBatchSize > 10_000) {
      throw new IllegalStateException("Remote desktop participant purge batch must be 1 to 10000");
    }
    this.history = history;
    this.sessions = sessions;
    this.retention = Duration.ofDays(retentionDays);
    this.purgeBatchSize = purgeBatchSize;
  }

  @Transactional(readOnly = true)
  public RemoteDesktopParticipantHistoryPage list(
      String sessionId, String tenantId, int limit, String cursor) {
    var session = sessions.require(sessionId);
    if (!tenantId.equals(session.tenantId())) {
      throw new TenantAccessDeniedException(sessionId);
    }
    return history.list(tenantId, sessionId, limit, cursor);
  }

  @Scheduled(cron = "${remote-desktop.participant-history-retention-cron:0 29 * * * *}")
  public void purgeExpiredHistory() {
    history.purgeTerminalBefore(Instant.now().minus(retention), purgeBatchSize);
  }
}
