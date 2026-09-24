package com.alanan.accounting.users;

import com.alanan.accounting.common.exception.ApiException;
import com.alanan.accounting.auth.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserDirectory directory;
    private final UserManagement management;
    public UserController(UserDirectory directory, UserManagement management) { this.directory = directory; this.management = management; }
    @GetMapping
    List<UserDirectory.UserSummary> list(@RequestParam(defaultValue = "0") int page) {
        if (page < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        return directory.list(page);
    }
    @GetMapping("/options")
    UserManagement.Options options() { return management.options(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    UserManagement.CreatedUser create(@Valid @RequestBody CreateUserRequest input, @AuthenticationPrincipal AuthUser user) {
        return management.create(input,user);
    }
}
