package com.alanan.accounting.customerpayments.api;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.customerpayments.application.CustomerPaymentService;
import com.alanan.accounting.customerpayments.application.CustomerPaymentQueries;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer-payments")
public class CustomerPaymentController {
    private final CustomerPaymentService service;
    private final CustomerPaymentQueries queries;
    public CustomerPaymentController(CustomerPaymentService service,CustomerPaymentQueries queries) { this.service=service; this.queries=queries; }
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@Valid @RequestBody CustomerPaymentRequest request,@AuthenticationPrincipal AuthUser user) { return service.create(request,user); }
    @GetMapping
    Object list(@RequestParam(defaultValue="0") int page,@RequestParam(required=false) UUID customerId) { return queries.list(page,customerId); }
    @GetMapping("/open-invoices")
    Object openInvoices(@RequestParam UUID customerId,@RequestParam(defaultValue="0") int page) { return queries.openInvoices(customerId,page); }
    @GetMapping("/{id}")
    Object detail(@PathVariable UUID id) { return queries.detail(id); }
}
