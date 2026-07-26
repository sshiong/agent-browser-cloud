package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "tenant_audit_heads")
public class TenantAuditHeadEntity {

  @Id
  @Column(name = "tenant_id")
  private String tenantId;

  @Column(name = "sequence_no", nullable = false)
  private long sequenceNo;

  @Column(name = "head_hash")
  private String headHash;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public TenantAuditHeadEntity() {}

  public String getTenantId() {
    return tenantId;
  }

  public long getSequenceNo() {
    return sequenceNo;
  }

  public String getHeadHash() {
    return headHash;
  }

  public void advance(long sequenceNo, String headHash, Instant updatedAt) {
    this.sequenceNo = sequenceNo;
    this.headHash = headHash;
    this.updatedAt = updatedAt;
  }
}
