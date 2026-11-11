package com.districtone.webv9.service;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class ComplaintApiDtos {
  private ComplaintApiDtos() {}

  public record CreateComplaintRequest(
      @NotNull Long memberId,
      @NotBlank @Size(max = 255) String subject,
      String description) {}

  public record AddComplaintHistoryRequest(@NotBlank String note) {}

  public record CustomerReference(Long memberId, String memberNo, String name, String contactNo) {}

  public record SaleReference(
      Long registrationId, String registrationNo, String formNo, String registrationStatus,
      Long bookingId, String bookingStatus, Long plotId, String plotNo, String blockName,
      String plotStatus) {}

  public record ComplaintHistoryEntry(
      Long historyId, String note, LocalDateTime createdAt, String recordedBy) {}

  public record ComplaintListItem(
      Long complaintId, String subject, String description, String status, LocalDateTime createdAt,
      CustomerReference customer, int historyCount) {}

  public record ComplaintPage(
      List<ComplaintListItem> items, int page, int pageSize, long totalElements) {}

  public record ComplaintDetail(
      Long complaintId, String subject, String description, String status, LocalDateTime createdAt,
      CustomerReference customer, List<SaleReference> saleReferences,
      List<ComplaintHistoryEntry> history) {}

  public record ComplaintCreated(Long complaintId, String status, LocalDateTime createdAt) {}

  public record ComplaintHistoryCreated(
      Long historyId, Long complaintId, LocalDateTime createdAt) {}
}
