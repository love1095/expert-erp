package com.experterp.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
