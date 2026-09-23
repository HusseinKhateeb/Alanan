package com.alanan.accounting.masterdata;

import com.alanan.accounting.auth.AuthUser;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers/quick")
public class QuickCustomerController {
    private final MasterDataService service;
    public QuickCustomerController(MasterDataService service) { this.service = service; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody QuickCustomerInput input, @AuthenticationPrincipal AuthUser user) {
        return service.quickCreateCustomer(input, user);
    }
}
