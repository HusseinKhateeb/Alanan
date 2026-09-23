package com.alanan.accounting.purchases.api;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.purchases.application.PurchaseService;
import com.alanan.accounting.purchases.application.PurchaseQueries;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {
    private final PurchaseService service;
    private final PurchaseQueries queries;
    public PurchaseController(PurchaseService service, PurchaseQueries queries) { this.service=service; this.queries=queries; }
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@Valid @RequestBody PurchaseRequest request, @AuthenticationPrincipal AuthUser user) { return service.create(request,user); }
    @GetMapping
    Object list(@RequestParam(defaultValue="0") int page, @RequestParam(required=false) UUID supplierId) { return queries.list(page,supplierId); }
    @GetMapping("/{id}")
    Object detail(@PathVariable UUID id) { return queries.detail(id); }
}
