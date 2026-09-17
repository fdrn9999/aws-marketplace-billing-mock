package io.github.fdrn9999.marketplace.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

import io.github.fdrn9999.marketplace.domain.Product;

@Repository
public class ProductRepository {

    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public Optional<Product> findByCode(String productCode) {
        return productCode == null ? Optional.empty() : Optional.ofNullable(products.get(productCode));
    }

    public List<Product> findAll() {
        return products.values().stream()
                .sorted((a, b) -> a.productCode().compareTo(b.productCode()))
                .toList();
    }

    public void replaceAll(Collection<Product> seed) {
        products.clear();
        seed.forEach(p -> products.put(p.productCode(), p));
    }
}
