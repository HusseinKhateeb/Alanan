package com.alanan.accounting.expenses;

import com.alanan.accounting.auth.AuthUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/expenses")
public class ExpenseController {
    private final ExpenseService service;
    public ExpenseController(ExpenseService service) { this.service=service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Object create(@Valid @RequestBody ExpenseRequest request,@AuthenticationPrincipal AuthUser user) { return service.create(request,user); }
    @GetMapping public Object list(@RequestParam(defaultValue="0") int page,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) UUID categoryId) { return service.list(page,from,to,categoryId); }
    @GetMapping("/{id}") public Object detail(@PathVariable UUID id) { return service.detail(id); }
}
