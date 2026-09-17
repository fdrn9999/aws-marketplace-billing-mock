package io.github.fdrn9999.marketplace.admin;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.domain.Product;
import io.github.fdrn9999.marketplace.store.ProductRepository;

/** 판매 중인 상품(리스팅) 목록. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public List<Product> products() {
        return products.findAll();
    }
}
