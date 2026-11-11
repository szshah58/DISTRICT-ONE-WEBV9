package com.districtone.webv9.repo;

import com.districtone.webv9.model.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {
  List<PaymentAllocation> findByPaymentIdOrderByLineNoAsc(Long paymentId);
}
