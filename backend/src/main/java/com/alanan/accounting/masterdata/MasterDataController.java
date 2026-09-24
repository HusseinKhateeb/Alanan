package com.alanan.accounting.masterdata;

import com.alanan.accounting.auth.AuthUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/{catalog:products|customers|suppliers|container-types|payment-methods|expense-categories}")
public class MasterDataController {
    private final MasterDataService service;
    public MasterDataController(MasterDataService service) { this.service = service; }
    @GetMapping
    Object list(@PathVariable String catalog, @RequestParam(defaultValue="") String q,
                @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="false") boolean activeOnly,
                @RequestParam(required=false) String phone, @AuthenticationPrincipal AuthUser user) {
        return service.list(Catalog.from(catalog), q, page, activeOnly, phone, user);
    }
    @GetMapping("/{id}")
    Map<String,Object> detail(@PathVariable String catalog, @PathVariable String id, @AuthenticationPrincipal AuthUser user) {
        return service.detail(Catalog.from(catalog), id, user);
    }
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String,Object> create(@PathVariable String catalog, @Valid @RequestBody MasterDataInput input,
                             @AuthenticationPrincipal AuthUser user) {
        return service.save(Catalog.from(catalog), null, input, user);
    }
    @PutMapping("/{id}")
    Map<String,Object> update(@PathVariable String catalog, @PathVariable String id,
                             @Valid @RequestBody MasterDataInput input, @AuthenticationPrincipal AuthUser user) {
        return service.save(Catalog.from(catalog), id, input, user);
    }
}
