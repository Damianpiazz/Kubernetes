package com.latiendita.orderService.order;

import com.latiendita.orderService.order.dto.CancelOrderRequest;
import com.latiendita.orderService.order.dto.CreateOrderRequest;
import com.latiendita.orderService.order.dto.OrderResponse;
import com.latiendita.orderService.order.dto.UpdateOrderStatusRequest;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{orderId}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{orderId}")
    public OrderResponse findById(@PathVariable Long orderId) {
        return orderService.findById(orderId);
    }

    @GetMapping
    public Page<OrderResponse> findByCustomer(Pageable pageable) {
        return orderService.findByCustomer(pageable);
    }

    @PostMapping("/{orderId}/status")
    public OrderResponse changeStatus(@PathVariable Long orderId,
                                      @Valid @RequestBody UpdateOrderStatusRequest request) {
        return orderService.changeStatus(orderId, request.status());
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@PathVariable Long orderId,
                                @Valid @RequestBody CancelOrderRequest request) {
        return orderService.cancel(orderId, request.reason());
    }
}
