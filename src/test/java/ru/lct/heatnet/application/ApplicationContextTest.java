package ru.lct.heatnet.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.lct.heatnet.persistence.JobRepository;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Boots the Spring context with the H2 test profile (the runtime database is PostgreSQL). */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextTest {

    @Autowired
    JobService jobService;
    @Autowired
    JobRepository repository;

    @Test
    void contextLoadsWithTestDatabase() {
        assertNotNull(jobService);
        assertNotNull(repository);
        repository.count();
    }
}
