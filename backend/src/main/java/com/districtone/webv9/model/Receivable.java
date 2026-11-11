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
import java.time.LocalDate;

@Entity
@Table(
    name = "webv9_receivables",
    indexes = {
        @Index(name = "ix_webv9_receivable_registration", columnList = "registration_id"),
        @Index(name = "ix_webv9_receivable_booking_plot_due", columnList = "booking_id,plot_id,charge_type_id,status,due_date"),
        @Index(name = "ix_webv9_receivable_plot", columnList = "plot_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class Receivable {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "member_id", nullable = false)
  private Long memberId;

  @Column(name = "registration_id")
  private Long registrationId;

  @Column(name = "booking_id")
  private Long bookingId;

  @Column(name = "plot_id")
  private Long plotId;

  @Column(name = "charge_type_id")
  private Long chargeTypeId;

  @Column(name = "due_date", nullable = false)
  private LocalDate dueDate;

  @Column(name = "original_amount", precision = 16, scale = 2, nullable = false)
  private BigDecimal originalAmount;

  @Column(name = "paid_amount", precision = 16, scale = 2, nullable = false)
  private BigDecimal paidAmount;

  @Column(name = "surcharge_amount", precision = 16, scale = 2, nullable = false)
  private BigDecimal surchargeAmount;

  @Column(name = "adjustment_amount", precision = 16, scale = 2, nullable = false)
  private BigDecimal adjustmentAmount;

  @Column(name = "status", length = 30, nullable = false)
  private String status;

  @Column(name = "description", length = 500)
  private String description;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;
}
