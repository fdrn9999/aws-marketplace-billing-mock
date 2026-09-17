package io.github.fdrn9999.marketplace.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Mock AWS 호출과 이벤트 전달에 쓰는 RestClient. 오류 상태 코드는 호출하는 쪽에서 직접 해석한다. */
@Configuration
public class HttpClientConfig {

    public static final String MARKETPLACE_CLIENT = "marketplaceRestClient";

    @Bean
    @Qualifier(MARKETPLACE_CLIENT)
    public RestClient marketplaceRestClient(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(requestFactory).build();
    }
}
