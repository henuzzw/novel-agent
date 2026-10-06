package com.novelagent.writing.api;

import com.novelagent.writing.application.BookScanService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/book-scan")
public class BookScanController {
    private final BookScanService service;
    public BookScanController(BookScanService service) { this.service = service; }
    @GetMapping public ResponseEntity<BookScanService.View> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.scan(projectId));
    }
}
