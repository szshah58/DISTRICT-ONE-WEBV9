package com.districtone.webv9.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments_receipts")
@Getter
@Setter
@NoArgsConstructor
public class Payment {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @ManyToOne
  @JoinColumn(name = "customer_id", nullable = false)
  private Customer customer;

  @Column(name = "amount", precision = 16, scale = 2, nullable = false)
  private BigDecimal amount;

  @Column(name = "payment_date", nullable = false)
  private LocalDate paymentDate;

  @Column(name = "payment_method", length = 30, nullable = false)
  private String method;

  @Column(name = "receipt_no", length = 80, nullable = false, unique = true)
  private String receiptNo;

  @Column(name = "payment_reference", length = 120)
  private String paymentReference;

  @Column(name = "deposit_reference", length = 120)
  private String referenceNo;

  @Column(name = "sales_classification", length = 20)
  private String salesClassification;

  @Column(name = "bank_instrument_type", length = 24)
  private String bankInstrumentType;

  @Column(name = "receiving_account", length = 180)
  private String receivingAccount;

  @Column(name = "status", length = 30, nullable = false)
  private String status = "RECOVERY_ENTRY";

  @Column(name = "verified_source_amount", precision = 16, scale = 2)
  private BigDecimal verifiedSourceAmount;

  @Column(name = "verification_reference", length = 255)
  private String verificationReference;

  @Column(name = "verification_remarks", length = 500)
  private String verificationRemarks;

  @Column(name = "verified_at")
  private LocalDateTime verifiedAt;

  public Long getId() {
    return id;
  }
}
