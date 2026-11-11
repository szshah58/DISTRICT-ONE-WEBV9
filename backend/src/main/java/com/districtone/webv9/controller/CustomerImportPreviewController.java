package com.districtone.webv9.controller;

import com.districtone.webv9.service.CustomerImportPreviewService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/customer-import")
public class CustomerImportPreviewController {
  private final CustomerImportPreviewService previewService;

  public CustomerImportPreviewController(CustomerImportPreviewService previewService) {
    this.previewService = previewService;
  }

  @PostMapping(path = "/preview", consumes = "multipart/form-data")
  public CustomerImportPreviewService.PreviewResult preview(@RequestPart("files") List<MultipartFile> files) {
    return previewService.preview(files);
  }
}
