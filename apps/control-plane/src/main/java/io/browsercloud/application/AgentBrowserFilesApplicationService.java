package io.browsercloud.application;

import static io.browsercloud.api.AgentBrowserFilesModels.*;

import io.browsercloud.application.AgentBrowserFileStageNodeGateway.StageRejectedException;
import io.browsercloud.application.AgentBrowserFileStageNodeGateway.StageUnavailableException;
import io.browsercloud.application.AgentBrowserFileUploadStore.UploadClaim;
import io.browsercloud.coordinator.BrowserStateRepository;
import io.browsercloud.coordinator.NodeEvent;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** PostgreSQL-authoritative Browser download queries and bounded terminal wait. */
@Service
public class AgentBrowserFilesApplicationService {
  private static final int MAX_WAITERS = 32;
  private final SessionRepository sessions;
  private final BrowserStateRepository states;
  private final AgentBrowserFileUploadStore uploads;
  private final AgentBrowserFileStageNodeGateway stageGateway;
  private final Semaphore waiters = new Semaphore(MAX_WAITERS);

  public AgentBrowserFilesApplicationService(
      SessionRepository sessions,
      BrowserStateRepository states,
      AgentBrowserFileUploadStore uploads,
      AgentBrowserFileStageNodeGateway stageGateway) {
    this.sessions = sessions;
    this.states = states;
    this.uploads = uploads;
    this.stageGateway = stageGateway;
  }

  public FileUploadView upload(
      String sessionId,
      String tenantId,
      String actorId,
      String idempotencyKey,
      String requestId,
      String targetRef,
      long targetRevision,
      long baseStateVersion,
      String baseContentHash,
      String filename,
      String mimeType,
      String contentSha256,
      MultipartFile content) {
    var normalizedFilename = filename == null ? "" : filename.strip();
    var normalizedMime =
        mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType.strip();
    var normalizedHash =
        contentSha256 == null ? "" : contentSha256.strip().toLowerCase(Locale.ROOT);
    validateUpload(
        idempotencyKey,
        targetRef,
        targetRevision,
        baseStateVersion,
        baseContentHash,
        normalizedFilename,
        normalizedMime,
        normalizedHash,
        content);
    var requestHash =
        PromptSecurityService.sha256(
            String.join(
                "|",
                sessionId,
                targetRef,
                Long.toString(targetRevision),
                Long.toString(baseStateVersion),
                baseContentHash.toLowerCase(Locale.ROOT),
                normalizedFilename,
                normalizedMime,
                normalizedHash,
                Long.toString(content.getSize())));
    var upload =
        uploads.claim(
            new UploadClaim(
                newId("afu_"),
                tenantId,
                sessionId,
                actorId,
                idempotencyKey,
                requestHash,
                requestId,
                targetRef,
                targetRevision,
                baseStateVersion,
                baseContentHash.toLowerCase(Locale.ROOT),
                normalizedFilename,
                normalizedMime,
                normalizedHash,
                content.getSize()));
    if (!"STAGING".equals(upload.state())) return AgentBrowserFileUploadStore.toView(upload);
    try (var stream = content.getInputStream()) {
      stageGateway.stage(
          new AgentBrowserFileStageNodeGateway.StageRequest(
              upload.uploadId(),
              upload.tenantId(),
              upload.sessionId(),
              upload.nodeId(),
              upload.coordinatorTerm(),
              upload.contextEpoch(),
              upload.filename(),
              upload.mimeType(),
              upload.contentSha256(),
              upload.contentBytes()),
          stream);
      return AgentBrowserFileUploadStore.toView(uploads.dispatch(upload.uploadId(), tenantId));
    } catch (StageRejectedException | StageUnavailableException exception) {
      var code = stableCode(exception, "AGENT_FILE_STAGE_FAILED");
      uploads.fail(upload.uploadId(), tenantId, code, requestId);
      throw new AgentBrowserFilesException(code);
    } catch (IOException exception) {
      uploads.fail(upload.uploadId(), tenantId, "AGENT_FILE_STREAM_FAILED", requestId);
      throw new AgentBrowserFilesException("AGENT_FILE_STREAM_FAILED");
    } catch (RuntimeException exception) {
      try {
        uploads.fail(upload.uploadId(), tenantId, "AGENT_FILE_DISPATCH_FAILED", requestId);
      } catch (RuntimeException ignored) {
        // Preserve the original failure; the authoritative ledger remains recoverable.
      }
      throw exception;
    }
  }

  public FileUploadView upload(String uploadId, String tenantId) {
    return uploads.get(uploadId, tenantId);
  }

  public DownloadListView downloads(String sessionId, String tenantId) {
    var state = requireState(sessionId, tenantId);
    return new DownloadListView(
        cursor(state),
        state.downloadEvidenceFresh(),
        !state.downloadEvidenceFresh(),
        state.downloads().stream().map(AgentBrowserFilesApplicationService::view).toList());
  }

  /**
   * Blocks one bounded HTTP request while repeatedly reading the PostgreSQL projection. The Browser
   * itself is never polled and the connection cap prevents waiters from exhausting request workers.
   */
  public DownloadView waitForDownload(
      String sessionId, String tenantId, String downloadId, int timeoutMs) {
    if (!waiters.tryAcquire())
      throw new AgentBrowserFilesException("DOWNLOAD_WAIT_CAPACITY_EXCEEDED");
    try {
      var deadline = Instant.now().plus(Duration.ofMillis(timeoutMs));
      while (true) {
        var state = requireState(sessionId, tenantId);
        if (!state.downloadEvidenceFresh()) {
          throw new AgentBrowserFilesException("DOWNLOAD_STATE_STALE");
        }
        var download =
            state.downloads().stream()
                .filter(candidate -> candidate.downloadId().equals(downloadId))
                .findFirst()
                .orElseThrow(() -> new AgentBrowserFilesException("DOWNLOAD_NOT_FOUND"));
        if (!download.status().equals("IN_PROGRESS")) return view(download);
        if (!Instant.now().isBefore(deadline)) {
          throw new AgentBrowserFilesException("DOWNLOAD_WAIT_TIMEOUT");
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new AgentBrowserFilesException("DOWNLOAD_WAIT_INTERRUPTED");
        }
      }
    } finally {
      waiters.release();
    }
  }

  private NodeEvent.StateUpdated requireState(String sessionId, String tenantId) {
    var session = sessions.require(sessionId);
    if (!session.tenantId().equals(tenantId)) throw new SessionNotFoundException(sessionId);
    return states
        .find(sessionId)
        .filter(snapshot -> snapshot.tenantId().equals(tenantId))
        .filter(snapshot -> snapshot.contextEpoch() == session.contextEpoch())
        .map(BrowserStateRepository.Snapshot::state)
        .orElseThrow(() -> new AgentBrowserFilesException("BROWSER_STATE_UNAVAILABLE"));
  }

  private static String cursor(NodeEvent.StateUpdated state) {
    return state.stateVersion() + ":" + state.targetRevision() + ":" + state.stateHash();
  }

  private static DownloadView view(NodeEvent.BrowserDownload value) {
    return new DownloadView(
        value.downloadId(),
        value.filename(),
        value.mimeType(),
        value.totalBytes(),
        value.receivedBytes(),
        value.progressBasisPoints() == null ? null : value.progressBasisPoints() / 10_000.0,
        value.status(),
        value.startedAt(),
        value.updatedAt());
  }

  private static void validateUpload(
      String idempotencyKey,
      String targetRef,
      long targetRevision,
      long baseStateVersion,
      String baseContentHash,
      String filename,
      String mimeType,
      String contentSha256,
      MultipartFile content) {
    if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
      throw new AgentBrowserFilesException("AGENT_FILE_IDEMPOTENCY_KEY_INVALID");
    }
    if (targetRef == null
        || targetRef.isBlank()
        || targetRef.length() > 128
        || targetRevision < 1
        || baseStateVersion < 1) {
      throw new AgentBrowserFilesException("AGENT_FILE_TARGET_INVALID");
    }
    if (!lowerHex(baseContentHash) || !lowerHex(contentSha256)) {
      throw new AgentBrowserFilesException("AGENT_FILE_SHA256_INVALID");
    }
    if (filename.isBlank()
        || filename.length() > 255
        || filename.contains("/")
        || filename.contains("\\")
        || filename.chars().anyMatch(Character::isISOControl)
        || mimeType.isBlank()
        || mimeType.length() > 255
        || mimeType.chars().anyMatch(Character::isISOControl)) {
      throw new AgentBrowserFilesException("AGENT_FILE_METADATA_INVALID");
    }
    if (content == null || content.isEmpty() || content.getSize() > 64L * 1024 * 1024) {
      throw new AgentBrowserFilesException("AGENT_FILE_SIZE_INVALID");
    }
  }

  private static boolean lowerHex(String value) {
    return value != null
        && value.length() == 64
        && value
            .chars()
            .allMatch(
                character ->
                    (character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'));
  }

  private static String stableCode(RuntimeException exception, String fallback) {
    var value = exception.getMessage();
    return value != null && value.matches("^[A-Z0-9_]{1,64}$") ? value : fallback;
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  public static final class AgentBrowserFilesException extends RuntimeException {
    public AgentBrowserFilesException(String code) {
      super(code);
    }
  }
}
