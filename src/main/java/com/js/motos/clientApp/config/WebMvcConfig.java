package com.js.motos.clientApp.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.js.motos.clientApp.security.CurrentUserArgumentResolver;

@Configuration
@EnableJpaAuditing
@EnableScheduling
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final String localStorageDir;
    private final boolean cloudinaryEnabled;

    public WebMvcConfig(CurrentUserArgumentResolver currentUserArgumentResolver,
                        @Value("${app.storage.local-dir:./uploads}") String localStorageDir,
                        @Value("${app.cloudinary.enabled:false}") boolean cloudinaryEnabled) {
        this.currentUserArgumentResolver = currentUserArgumentResolver;
        this.localStorageDir = localStorageDir;
        this.cloudinaryEnabled = cloudinaryEnabled;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /**
     * Serves locally-stored receipts in development only. In production Cloudinary
     * is enabled and this handler is not registered at all.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (cloudinaryEnabled) {
            return;
        }
        String location = java.nio.file.Paths.get(localStorageDir)
                .toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/files/**").addResourceLocations(location);
    }
}