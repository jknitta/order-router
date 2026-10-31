package com.synapse.orderrouter.catalog;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class CatalogConfig {

    @Bean
    public Catalog catalog() {
        try (Reader suppliers = classpathReader("data/suppliers.csv");
             Reader products = classpathReader("data/products.csv")) {
            return CatalogLoader.load(suppliers, products);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the CSV files from the classpath", e);
        }
    }

    private static Reader classpathReader(String path) throws IOException {
        return new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
    }
}
