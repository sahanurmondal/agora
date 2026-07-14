package com.agora.catalog.product;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    public record CreateReq(String name, String description, long priceCents, String imageKey) {
    }

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Product> create(@RequestBody CreateReq req,
                                          @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String sellerId) {
        if (req.name() == null || req.name().isBlank() || req.priceCents() <= 0) {
            return ResponseEntity.badRequest().build();
        }
        Product p = service.create(req.name(), req.description() == null ? "" : req.description(),
                req.priceCents(), sellerId, req.imageKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(p);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> get(@PathVariable long id) {
        return service.get(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> onError(Exception e) {
        return ResponseEntity.internalServerError().body(Map.of("error", e.getClass().getSimpleName()));
    }
}
