package com.agora.catalog.product;

public record Product(long id, String name, String description, long priceCents, String sellerId,
                      String imageKey) {
}
