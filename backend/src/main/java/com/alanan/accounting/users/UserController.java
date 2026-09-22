package com.alanan.accounting.users;

import com.alanan.accounting.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserDirectory directory;
    public UserController(UserDirectory directory) { this.directory = directory; }
    @GetMapping
    List<UserDirectory.UserSummary> list(@RequestParam(defaultValue = "0") int page) {
        if (page < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        return directory.list(page);
    }
}
