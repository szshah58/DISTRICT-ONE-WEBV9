package com.districtone.webv9.repo;

import com.districtone.webv9.model.Receivable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReceivableRepository extends JpaRepository<Receivable, Long> {
  List<Receivable> findByRegistrationIdAndBookingIdAndPlotIdAndChargeTypeIdOrderByDueDateAsc(
      Long registrationId, Long bookingId, Long plotId, Long chargeTypeId);

}
