package com.pnomeer.lab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "lab.storage.root=${java.io.tmpdir}/engineering-lab-tests-${random.uuid}")
class EngineeringLabApplicationTests {

    @Test
    void contextLoads() {
    }

}
