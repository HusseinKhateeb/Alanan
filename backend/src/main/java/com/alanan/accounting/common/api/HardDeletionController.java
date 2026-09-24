package com.alanan.accounting.common.api;

import com.alanan.accounting.auth.AuthUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/deletions")
public class HardDeletionController {
    private final HardDeletionService service;
    public HardDeletionController(HardDeletionService service) { this.service=service; }
    @DeleteMapping("/{kind}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String kind,@PathVariable UUID id,@Valid @RequestBody HardDeleteRequest request,@AuthenticationPrincipal AuthUser actor) {
        service.delete(kind,id,request,actor);
    }
}
