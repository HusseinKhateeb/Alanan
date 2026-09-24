package com.alanan.accounting.inventory;

import com.alanan.accounting.auth.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory/adjustments")
public class OpeningStockController {
    private final OpeningStockService service;
    public OpeningStockController(OpeningStockService service) { this.service=service; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Object add(@Valid @RequestBody OpeningStockRequest request, @AuthenticationPrincipal AuthUser user) { return service.add(request,user); }
}
