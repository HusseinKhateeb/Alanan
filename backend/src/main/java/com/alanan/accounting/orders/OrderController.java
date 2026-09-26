package com.alanan.accounting.orders;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.common.api.PagedRows;
import com.alanan.accounting.sales.api.SaleRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orders;
    public OrderController(OrderService orders) { this.orders=orders; }
    @GetMapping public PagedRows.Page list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String q) { return orders.list(page,q); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody SaleRequest request,@AuthenticationPrincipal AuthUser user) { return orders.create(request,user); }
    @GetMapping("/{id}") public Map<String,Object> detail(@PathVariable UUID id) { return orders.detail(id); }
    @PutMapping("/{id}") public Map<String,Object> edit(@PathVariable UUID id,@Valid @RequestBody OrderChangeRequest request,@AuthenticationPrincipal AuthUser user) { return orders.edit(id,request,user); }
    @PostMapping("/{id}/deliver") public Map<String,Object> deliver(@PathVariable UUID id,@Valid @RequestBody DeliverOrderRequest request,@AuthenticationPrincipal AuthUser user) { return orders.deliver(id,request,user); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,@Valid @RequestBody DeleteOrderRequest request,@AuthenticationPrincipal AuthUser user) { orders.delete(id,request,user); }
}
