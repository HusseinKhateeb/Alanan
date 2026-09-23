package com.alanan.accounting.purchases.api;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.pdf.InvoicePdfService;
import com.alanan.accounting.purchases.application.PurchaseService;
import com.alanan.accounting.purchases.application.PurchaseQueries;
import com.alanan.accounting.purchases.application.PurchaseCancellationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {
    private final PurchaseService service;
    private final PurchaseQueries queries;
    private final PurchaseCancellationService cancellations;
    private final InvoicePdfService pdf;
    public PurchaseController(PurchaseService service, PurchaseQueries queries, PurchaseCancellationService cancellations,InvoicePdfService pdf) { this.service=service; this.queries=queries; this.cancellations=cancellations; this.pdf=pdf; }
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Object create(@Valid @RequestBody PurchaseRequest request, @AuthenticationPrincipal AuthUser user) { return service.create(request,user); }
    @GetMapping
    Object list(@RequestParam(defaultValue="0") int page, @RequestParam(required=false) UUID supplierId) { return queries.list(page,supplierId); }
    @GetMapping("/{id}")
    Object detail(@PathVariable UUID id) { return queries.detail(id); }
    @GetMapping(value="/{id}/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> pdf(@PathVariable UUID id) throws java.io.IOException {
        var invoice=queries.detail(id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"purchase-"+id+".pdf\"")
            .contentType(MediaType.APPLICATION_PDF).body(pdf.render(invoice,false));
    }
    @PostMapping("/{id}/cancel")
    Object cancel(@PathVariable UUID id,@Valid @RequestBody CancelPurchaseRequest request,@AuthenticationPrincipal AuthUser user) { return cancellations.cancel(id,request,user); }
}
