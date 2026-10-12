package com.latiendita.paymentService.payment;

import com.latiendita.paymentService.payment.dto.CreatePaymentRequest;
import com.latiendita.paymentService.payment.dto.PaymentResponse;
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
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse created = paymentService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{paymentId}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse findById(@PathVariable Long paymentId) {
        return paymentService.findById(paymentId);
    }

    @GetMapping
    public Page<PaymentResponse> findByCustomer(Pageable pageable) {
        return paymentService.findByCustomer(pageable);
    }

    @GetMapping("/order/{orderId}")
    public PaymentResponse findByOrderId(@PathVariable Long orderId) {
        return paymentService.findByOrderId(orderId);
    }
}
