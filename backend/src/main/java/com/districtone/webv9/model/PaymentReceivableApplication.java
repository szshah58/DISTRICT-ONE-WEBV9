package com.districtone.webv9.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "webv9_payment_receivable_applications",
    indexes = {
        @Index(name = "ix_webv9_pra_allocation", columnList = "allocation_id"),
        @Index(name = "ix_webv9_pra_receivable", columnList = "receivable_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class PaymentReceivableApplication {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "allocation_id", nullable = false)
  private Long allocationId;

  @Column(name = "receivable_id", nullable = false)
  private Long receivableId;

  @Column(name = "applied_amount", precision = 16, scale = 2, nullable = false)
  private BigDecimal appliedAmount;

  @Column(name = "created_by")
  private Long createdBy;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;
}
