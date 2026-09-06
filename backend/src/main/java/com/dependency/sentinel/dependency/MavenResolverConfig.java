package com.dependency.sentinel.dependency;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MavenResolverConfig {

    @Bean(destroyMethod = "shutdown")
    public RepositorySystem repositorySystem() {
        return new RepositorySystemSupplier().get();
    }

    @Bean
    public RemoteRepository mavenCentralRepository() {
        return new RemoteRepository.Builder(
                "central", "default", "https://repo.maven.apache.org/maven2/")
                .build();
    }
}
