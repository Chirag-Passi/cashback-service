package com.js.motos.clientApp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the whole context, which requires a reachable PostgreSQL because Flyway
 * runs and Hibernate validates the mapping against the real schema — the check
 * that catches entity/migration drift.
 *
 * <p>Skipped unless {@code DB_URL} is set, so {@code mvn test} still passes on a
 * machine with no database. To run it:
 *
 * <pre>
 * DB_URL=jdbc:postgresql://localhost:5432/motos DB_USERNAME=motos DB_PASSWORD=motos ./mvnw test
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ClientAppApplicationTests {

	@Test
	void contextLoads() {
	}

}