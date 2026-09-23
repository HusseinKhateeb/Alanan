package com.alanan.accounting.audit;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/audit")
public class AuditController {
    private final AuditQueries queries;
    public AuditController(AuditQueries queries) { this.queries=queries; }
    @GetMapping
    public Object list(@RequestParam(defaultValue="0") int page,@RequestParam(required=false) String type,
        @RequestParam(required=false) UUID actor,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        return queries.list(page,type,actor,from,to);
    }
}
