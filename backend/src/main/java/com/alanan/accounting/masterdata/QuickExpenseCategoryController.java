package com.alanan.accounting.masterdata;

import com.alanan.accounting.auth.AuthUser;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/expense-categories/quick")
public class QuickExpenseCategoryController {
    private final MasterDataService service;
    public QuickExpenseCategoryController(MasterDataService service) { this.service=service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody QuickExpenseCategoryInput input,@AuthenticationPrincipal AuthUser user) {
        return service.quickCreateExpenseCategory(input,user);
    }
}
