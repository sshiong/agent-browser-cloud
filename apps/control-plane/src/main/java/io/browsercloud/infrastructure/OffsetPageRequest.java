package io.browsercloud.infrastructure;

import java.io.Serial;
import java.io.Serializable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** 支持 REST offset/limit 语义的 Spring Data Pageable。 */
public record OffsetPageRequest(long offset, int pageSize, Sort sort)
    implements Pageable, Serializable {

  @Serial private static final long serialVersionUID = 1L;

  public OffsetPageRequest {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be non-negative");
    }
    if (pageSize < 1) {
      throw new IllegalArgumentException("pageSize must be positive");
    }
    sort = sort == null ? Sort.unsorted() : sort;
  }

  @Override
  public int getPageNumber() {
    return Math.toIntExact(offset / pageSize);
  }

  @Override
  public int getPageSize() {
    return pageSize;
  }

  @Override
  public long getOffset() {
    return offset;
  }

  @Override
  public Sort getSort() {
    return sort;
  }

  @Override
  public Pageable next() {
    return new OffsetPageRequest(offset + pageSize, pageSize, sort);
  }

  @Override
  public Pageable previousOrFirst() {
    return hasPrevious() ? new OffsetPageRequest(offset - pageSize, pageSize, sort) : first();
  }

  @Override
  public Pageable first() {
    return new OffsetPageRequest(0, pageSize, sort);
  }

  @Override
  public Pageable withPage(int pageNumber) {
    if (pageNumber < 0) {
      throw new IllegalArgumentException("pageNumber must be non-negative");
    }
    return new OffsetPageRequest((long) pageNumber * pageSize, pageSize, sort);
  }

  @Override
  public boolean hasPrevious() {
    return offset >= pageSize;
  }
}
