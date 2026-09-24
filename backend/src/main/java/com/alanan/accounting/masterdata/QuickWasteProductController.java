package com.alanan.accounting.masterdata;

import com.alanan.accounting.auth.AuthUser;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products/quick-waste")
public class QuickWasteProductController {
    private final MasterDataService service;
    public QuickWasteProductController(MasterDataService service) { this.service=service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody QuickWasteProductInput input,@AuthenticationPrincipal AuthUser user) {
        return service.quickCreateWasteProduct(input,user);
    }
}
