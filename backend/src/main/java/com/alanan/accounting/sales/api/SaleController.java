package com.alanan.accounting.sales.api;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.common.pdf.InvoicePdfService;
import com.alanan.accounting.sales.application.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SaleController {
    private final SaleService service;
    private final SaleQueries queries;
    private final SaleCancellationService cancellations;
    private final InvoicePdfService pdf;
    public SaleController(SaleService service,SaleQueries queries,SaleCancellationService cancellations,InvoicePdfService pdf) { this.service=service; this.queries=queries; this.cancellations=cancellations; this.pdf=pdf; }
    @PostMapping("/sales") @ResponseStatus(HttpStatus.CREATED)
    public SaleService.Receipt create(@Valid @RequestBody SaleRequest request,@AuthenticationPrincipal AuthUser user) { return service.create(request,user); }
    @GetMapping("/sales") public PagedRows.Page list(@RequestParam(defaultValue="0") int page,@RequestParam(required=false) UUID customerId,
        @RequestParam(defaultValue="") String q) { return queries.list(page,customerId,q); }
    @GetMapping("/sales/{id}") public Map<String,Object> detail(@PathVariable UUID id,@AuthenticationPrincipal AuthUser user) { return queries.detail(id,user); }
    @GetMapping(value="/sales/{id}/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id,@AuthenticationPrincipal AuthUser user) throws java.io.IOException {
        var invoice=queries.detail(id,user);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"sale-"+id+".pdf\"")
            .contentType(MediaType.APPLICATION_PDF).body(pdf.render(invoice,true));
    }
    @PostMapping("/sales/{id}/cancel") public Map<String,Object> cancel(@PathVariable UUID id,@Valid @RequestBody CancelSaleRequest request,@AuthenticationPrincipal AuthUser user) { return cancellations.cancel(id,request,user); }
    @GetMapping("/customer-balances") public PagedRows.Page balances(@RequestParam(defaultValue="0") int page) { return queries.balances(page); }
}
