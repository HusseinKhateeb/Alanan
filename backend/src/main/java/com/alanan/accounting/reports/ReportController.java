package com.alanan.accounting.reports;

import com.alanan.accounting.auth.AuthUser;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/reports")
public class ReportController {
    private final ReportQueries queries;
    public ReportController(ReportQueries queries) { this.queries=queries; }
    @GetMapping("/{kind}")
    Object report(@PathVariable String kind,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue="0") int page,@AuthenticationPrincipal AuthUser user) {
        return queries.report(kind,from,to,page,user);
    }
}
