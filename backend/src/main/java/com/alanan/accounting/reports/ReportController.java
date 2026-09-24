package com.alanan.accounting.reports;

import com.alanan.accounting.auth.AuthUser;
import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/reports")
public class ReportController {
    private final ReportQueries queries;
    private final ReportCsvService csv;
    public ReportController(ReportQueries queries,ReportCsvService csv) { this.queries=queries; this.csv=csv; }
    @GetMapping("/{kind}")
    Object report(@PathVariable String kind,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(defaultValue="0") int page,@AuthenticationPrincipal AuthUser user) {
        return queries.report(kind,from,to,page,user);
    }
    @GetMapping(value="/{kind}/csv",produces="text/csv")
    ResponseEntity<byte[]> csv(@PathVariable String kind,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=alanan-"+kind+"-"+from+"-"+to+".csv")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8))
            .body(csv.export(kind,from,to,user));
    }
}
