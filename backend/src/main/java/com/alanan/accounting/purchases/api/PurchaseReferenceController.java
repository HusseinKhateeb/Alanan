package com.alanan.accounting.purchases.api;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.inventory.InventoryQueries;
import com.alanan.accounting.suppliers.SupplierBalanceQueries;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class PurchaseReferenceController {
    private final InventoryQueries inventory;
    private final SupplierBalanceQueries suppliers;
    public PurchaseReferenceController(InventoryQueries inventory,SupplierBalanceQueries suppliers) { this.inventory=inventory; this.suppliers=suppliers; }
    @GetMapping("/api/supplier-balances")
    Object suppliers(@RequestParam(defaultValue="0") int page) { return suppliers.list(page); }
    @GetMapping("/api/inventory")
    Object stock(@RequestParam(defaultValue="0") int page,@AuthenticationPrincipal AuthUser user) { return inventory.stock(page,user); }
    @GetMapping("/api/inventory/movements")
    Object movements(@RequestParam UUID productId,@RequestParam(defaultValue="0") int page,@AuthenticationPrincipal AuthUser user) {
        return inventory.movements(productId,page,user);
    }
}
