package com.districtone.webv9.repo;

import com.districtone.webv9.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
  @Query("select coalesce(sum(p.amount),0) from Payment p where p.status = 'POSTED'")
  BigDecimal postedTotal();

  long countByStatus(String status);

  @Query("select count(p) from Payment p where p.status in :statuses")
  long countByStatusIn(@Param("statuses") Collection<String> statuses);
}
