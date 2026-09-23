package com.alanan.accounting.waste;

import com.alanan.accounting.auth.AuthUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/waste")
public class WasteController {
    private final WasteService service;
    public WasteController(WasteService service) { this.service=service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Object create(@Valid @RequestBody WasteRequest request,@AuthenticationPrincipal AuthUser user) { return service.create(request,user); }
    @GetMapping public Object list(@RequestParam(defaultValue="0") int page,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) UUID productId,@AuthenticationPrincipal AuthUser user) { return service.list(page,from,to,productId,user); }
    @GetMapping("/{id}") public Object detail(@PathVariable UUID id,@AuthenticationPrincipal AuthUser user) { return service.detail(id,user); }
}
