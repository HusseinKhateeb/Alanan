package com.alanan.accounting.dashboard;

import com.alanan.accounting.auth.AuthUser;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardQueries queries;
    public DashboardController(DashboardQueries queries) { this.queries=queries; }
    @GetMapping public Map<String,Object> overview(@AuthenticationPrincipal AuthUser user) { return queries.overview(user); }
}
