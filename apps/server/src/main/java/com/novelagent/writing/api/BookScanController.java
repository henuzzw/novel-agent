package com.novelagent.writing.api;

import com.novelagent.writing.application.BookScanService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全书规则巡检。
 *
 * <p>查询既有正史摘要、台账状态与来源覆盖检查。此巡检不是全文文学通读，不触发生成或自动润色。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/book-scan")
public class BookScanController {
    private final BookScanService service;
    public BookScanController(BookScanService service) { this.service = service; }
    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping public ResponseEntity<BookScanService.View> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.scan(projectId));
    }
}
