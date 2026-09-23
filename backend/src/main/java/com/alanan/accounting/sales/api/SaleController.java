package com.alanan.accounting.sales.api;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.sales.application.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SaleController {
    private final SaleService service;
    private final SaleQueries queries;
    private final SaleCancellationService cancellations;
    public SaleController(SaleService service,SaleQueries queries,SaleCancellationService cancellations) { this.service=service; this.queries=queries; this.cancellations=cancellations; }
    @PostMapping("/sales") @ResponseStatus(HttpStatus.CREATED)
    public SaleService.Receipt create(@Valid @RequestBody SaleRequest request,@AuthenticationPrincipal AuthUser user) { return service.create(request,user); }
    @GetMapping("/sales") public PagedRows.Page list(@RequestParam(defaultValue="0") int page,@RequestParam(required=false) UUID customerId) { return queries.list(page,customerId); }
    @GetMapping("/sales/{id}") public Map<String,Object> detail(@PathVariable UUID id,@AuthenticationPrincipal AuthUser user) { return queries.detail(id,user); }
    @PostMapping("/sales/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id,@Valid @RequestBody CancelSaleRequest request,@AuthenticationPrincipal AuthUser user) { return cancellations.cancel(id,request,user); }
    @GetMapping("/customer-balances") public PagedRows.Page balances(@RequestParam(defaultValue="0") int page) { return queries.balances(page); }
}
