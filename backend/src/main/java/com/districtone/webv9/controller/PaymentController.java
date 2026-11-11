package com.districtone.webv9.controller;

import com.districtone.webv9.model.Payment;
import com.districtone.webv9.repo.PaymentRepository;
import com.districtone.webv9.service.RecoveryEntryCreationService;
import com.districtone.webv9.service.RecoveryEntryRequest;
import com.districtone.webv9.security.ProjectAccessService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
  private final PaymentRepository payments;
  private final RecoveryEntryCreationService recoveryEntries;
  private final ProjectAccessService projectAccess;

  public PaymentController(PaymentRepository payments, RecoveryEntryCreationService recoveryEntries,
                           ProjectAccessService projectAccess) {
    this.payments = payments;
    this.recoveryEntries = recoveryEntries;
    this.projectAccess = projectAccess;
  }

  @GetMapping
  public List<Payment> list(Authentication auth) {
    return payments.findAll().stream()
        .filter(payment -> projectAccess.canAccessPayment(auth.getName(), payment.getId())).toList();
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody RecoveryEntryRequest request, Authentication auth) {
    if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
    if (request.allocationLines() != null && !request.allocationLines().isEmpty()) {
      request.allocationLines().forEach(line -> projectAccess.requireBooking(auth.getName(), line.bookingId()));
    } else if (request.allocationScopeBookingId() != null) {
      projectAccess.requireBooking(auth.getName(), request.allocationScopeBookingId());
    } else if (!projectAccess.isSuperAdmin(auth.getName())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Receipt entry must be tied to a booking in your project/location scope.");
    }
    return ResponseEntity.status(201).body(recoveryEntries.create(request, auth != null ? auth.getName() : null));
  }
}
