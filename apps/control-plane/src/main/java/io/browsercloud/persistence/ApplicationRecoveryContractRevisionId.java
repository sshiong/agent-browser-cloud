package io.browsercloud.persistence;

import java.io.Serializable;
import java.util.Objects;

public class ApplicationRecoveryContractRevisionId implements Serializable {
  private String contractId;
  private long contractVersion;

  public ApplicationRecoveryContractRevisionId() {}

  public ApplicationRecoveryContractRevisionId(String contractId, long contractVersion) {
    this.contractId = contractId;
    this.contractVersion = contractVersion;
  }

  @Override
  public boolean equals(Object value) {
    if (this == value) return true;
    if (!(value instanceof ApplicationRecoveryContractRevisionId other)) return false;
    return contractVersion == other.contractVersion && Objects.equals(contractId, other.contractId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(contractId, contractVersion);
  }
}
