package io.github.fdrn9999.marketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MarketplaceBillingApplication {

	public static void main(String[] args) {
		SpringApplication.run(MarketplaceBillingApplication.class, args);
	}

}
