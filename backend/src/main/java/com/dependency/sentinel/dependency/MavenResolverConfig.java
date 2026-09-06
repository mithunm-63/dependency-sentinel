package com.dependency.sentinel.dependency;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
public class MavenResolverConfig {

    @Bean(destroyMethod = "shutdown")
    public RepositorySystem repositorySystem() {
        return new RepositorySystemSupplier().get();
    }

    @Bean
    public RepositorySystemSession repositorySystemSession(RepositorySystem repositorySystem) {
        var session = MavenRepositorySystemUtils.newSession();
        File localRepositoryDir = new File(System.getProperty("java.io.tmpdir"), "dependency-sentinel-m2");
        LocalRepository localRepository = new LocalRepository(localRepositoryDir);
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, localRepository));
        return session;
    }

    @Bean
    public RemoteRepository mavenCentralRepository() {
        return new RemoteRepository.Builder(
                "central", "default", "https://repo.maven.apache.org/maven2/")
                .build();
    }
}
