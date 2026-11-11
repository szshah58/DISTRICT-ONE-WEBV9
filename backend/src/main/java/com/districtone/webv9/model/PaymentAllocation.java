package com.districtone.webv9.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "webv9_payment_allocations",
    uniqueConstraints = @UniqueConstraint(name = "uq_webv9_pa_payment_line", columnNames = {"payment_id", "line_no"}),
    indexes = {
        @Index(name = "ix_webv9_pa_registration", columnList = "registration_id"),
        @Index(name = "ix_webv9_pa_booking_plot", columnList = "booking_id,plot_id"),
        @Index(name = "ix_webv9_pa_plot", columnList = "plot_id"),
        @Index(name = "ix_webv9_pa_charge_type", columnList = "charge_type_id"),
        @Index(name = "ix_webv9_pa_schedule", columnList = "installment_schedule_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
public class PaymentAllocation {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "payment_id", nullable = false)
  private Long paymentId;

  @Column(name = "line_no", nullable = false)
  private Integer lineNo;

  @Column(name = "registration_id", nullable = false)
  private Long registrationId;

  @Column(name = "booking_id", nullable = false)
  private Long bookingId;

  @Column(name = "plot_id", nullable = false)
  private Long plotId;

  @Column(name = "charge_type_id", nullable = false)
  private Long chargeTypeId;

  /** The receivable this active allocation reserves. Legacy allocation rows may not have one. */
  @Column(name = "receivable_id")
  private Long receivableId;

  @Column(name = "installment_schedule_id")
  private Long installmentScheduleId;

  @Column(name = "allocated_amount", precision = 16, scale = 2, nullable = false)
  private BigDecimal allocatedAmount;

  @Column(name = "line_remarks", length = 500)
  private String lineRemarks;

  @Column(name = "created_by")
  private Long createdBy;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;
}
