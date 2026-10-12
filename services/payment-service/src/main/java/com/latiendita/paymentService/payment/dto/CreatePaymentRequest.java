package com.latiendita.paymentService.payment.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePaymentRequest(
        @NotNull(message = "no debe estar en blanco")
        Long orderId,

        @NotBlank(message = "no debe estar en blanco")
        @Email(message = "debe ser un correo válido")
        String payerEmail) {}
