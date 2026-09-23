package com.alanan.accounting.supplierpayments.api;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.supplierpayments.application.SupplierPaymentService;
import com.alanan.accounting.supplierpayments.application.SupplierPaymentQueries;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/supplier-payments")
public class SupplierPaymentController {
    private final SupplierPaymentService service;
    private final SupplierPaymentQueries queries;
    public SupplierPaymentController(SupplierPaymentService service,SupplierPaymentQueries queries) { this.service=service; this.queries=queries; }
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@Valid @RequestBody SupplierPaymentRequest request,@AuthenticationPrincipal AuthUser user) { return service.create(request,user); }
    @GetMapping
    Object list(@RequestParam(defaultValue="0") int page,@RequestParam(required=false) UUID supplierId) { return queries.list(page,supplierId); }
    @GetMapping("/open-invoices")
    Object openInvoices(@RequestParam UUID supplierId,@RequestParam(defaultValue="0") int page) { return queries.openInvoices(supplierId,page); }
    @GetMapping("/{id}")
    Object detail(@PathVariable UUID id) { return queries.detail(id); }
}
