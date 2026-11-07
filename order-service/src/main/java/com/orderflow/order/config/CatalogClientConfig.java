package com.orderflow.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/** Cliente de catalog: LoadBalancer + token client credentials + circuit breaker (SPEC §6, §12). */
@Configuration
public class CatalogClientConfig {

  public static final String CIRCUIT_BREAKER = "catalog";
  static final String REGISTRATION = "catalog";
  private static final String CATALOG_URL = "http://catalog-service";

  /** El token es de la aplicación, no del comprador: se guarda bajo un único principal. */
  private static final AnonymousAuthenticationToken SERVICE_PRINCIPAL =
      new AnonymousAuthenticationToken(
          "order-service", "order-service", AuthorityUtils.createAuthorityList("ROLE_SERVICE"));

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  OAuth2AuthorizedClientManager authorizedClientManager(
      ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
    return new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
  }

  @Bean
  @LoadBalanced
  RestClient.Builder loadBalancedRestClientBuilder() {
    return RestClient.builder();
  }

  @Bean
  RestClient catalogRestClient(
      @LoadBalanced RestClient.Builder builder, OAuth2AuthorizedClientManager clientManager) {
    var oauth = new OAuth2ClientHttpRequestInterceptor(clientManager);
    oauth.setClientRegistrationIdResolver(request -> REGISTRATION);
    oauth.setPrincipalResolver(request -> SERVICE_PRINCIPAL);

    // Timeouts de SPEC §12. "Sin retry" también exige spring.cloud.loadbalancer.retry.enabled=false
    // (config-repo/order-service.yml): por defecto el LoadBalancer reintenta en otra instancia.
    var httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(2));

    return builder
        .baseUrl(CATALOG_URL)
        .requestFactory(requestFactory)
        .requestInterceptor(oauth)
        .build();
  }

  @Bean
  Customizer<Resilience4JCircuitBreakerFactory> catalogCircuitBreaker() {
    var config =
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .minimumNumberOfCalls(10)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .build();
    return factory -> factory.configure(builder -> builder.circuitBreakerConfig(config), CIRCUIT_BREAKER);
  }
}
