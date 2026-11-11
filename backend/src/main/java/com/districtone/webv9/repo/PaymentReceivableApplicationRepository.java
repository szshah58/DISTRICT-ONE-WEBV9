package com.districtone.webv9.repo;

import com.districtone.webv9.model.PaymentReceivableApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentReceivableApplicationRepository extends JpaRepository<PaymentReceivableApplication, Long> {
  List<PaymentReceivableApplication> findByAllocationIdOrderByIdAsc(Long allocationId);

  List<PaymentReceivableApplication> findByReceivableIdOrderByIdAsc(Long receivableId);
}
