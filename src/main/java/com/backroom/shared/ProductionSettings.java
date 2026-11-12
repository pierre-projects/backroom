package com.backroom.shared;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup in the prod profile when BACKROOM_PUBLIC_URL is missing or unusable. That address is printed into
 * every QR label, so starting with a placeholder would silently produce labels that point nowhere. Runs as a bean
 * factory post-processor so it fails before any bean is created: no database connection, no migration.
 */
@Component
@Profile("prod")
class ProductionSettings implements BeanFactoryPostProcessor, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) { this.environment = environment; }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        requireHttpsPublicUrl(environment.getProperty("backroom.public-base-url"));
    }

    static void requireHttpsPublicUrl(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            throw new IllegalStateException("BACKROOM_PUBLIC_URL must be set in production: it is the permanent HTTPS address printed on QR labels.");
        }
        try {
            var uri = new URI(publicUrl.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("BACKROOM_PUBLIC_URL must be an https:// address without a query or fragment, for example https://stock.example.com");
            }
        } catch (URISyntaxException invalid) {
            throw new IllegalStateException("BACKROOM_PUBLIC_URL is not a valid address.", invalid);
        }
    }
}
