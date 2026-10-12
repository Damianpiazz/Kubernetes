package com.latiendita.paymentService.webhook;

import com.latiendita.paymentService.provider.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping("/mercado-pago")
    public ResponseEntity<Void> handle(
            @RequestHeader(value = "x-signature", required = false) String signature,
            @RequestHeader(value = "x-request-id", required = false) String requestId,
            @RequestBody(required = false) WebhookNotification notification,
            @RequestParam(value = "data.id", required = false) String queryResourceId,
            @RequestParam(value = "type", required = false) String queryType) {

        webhookService.handle(ProviderType.MERCADO_PAGO, signature, requestId,
                notification, queryResourceId, queryType);

        return ResponseEntity.ok().build();
    }
}
