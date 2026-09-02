package com.kervan.order.web;

import com.kervan.order.application.OrderService;
import com.kervan.order.application.command.PlaceOrderCommand;
import com.kervan.order.web.dto.OrderResponse;
import com.kervan.order.web.dto.PlaceOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Sipariş oluşturma ve sorgulama")
class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "Sipariş oluştur",
            description = "Siparişi kaydeder ve OrderPlaced olayını aynı transaction "
                    + "içinde outbox tablosuna yazar.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sipariş oluşturuldu"),
            @ApiResponse(responseCode = "400", description = "Doğrulama hatası")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return OrderResponse.from(orderService.placeOrder(toCommand(request)));
    }

    @Operation(summary = "Siparişi id ile getir")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sipariş bulundu"),
            @ApiResponse(responseCode = "404", description = "Sipariş bulunamadı")
    })
    @GetMapping("/{id}")
    OrderResponse getOrder(@PathVariable String id) {
        return OrderResponse.from(orderService.getOrder(id));
    }

    private PlaceOrderCommand toCommand(PlaceOrderRequest request) {
        return new PlaceOrderCommand(
                request.customerId(),
                request.currency(),
                request.lines().stream()
                        .map(line -> new PlaceOrderCommand.Line(
                                line.productId(),
                                line.sku(),
                                line.quantity(),
                                line.unitPrice()))
                        .toList());
    }
}
