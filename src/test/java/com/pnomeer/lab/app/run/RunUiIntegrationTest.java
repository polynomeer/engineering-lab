package com.pnomeer.lab.app.run;

import com.pnomeer.pipeline.ExcelPipelineApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=ExcelPipelineApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"lab.storage.root=${java.io.tmpdir}/lab-ui-tests-${random.uuid}", "lab.storage.page-limit=2", "lab.storage.preview-limit=2", "lab.artifacts.download.max-bytes=4096"})
class RunUiIntegrationTest {
    @LocalServerPort int port;
    @Test void servesUiAndAssetsWithoutShadowingRunApi() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/runs/ui", "/run-browser/app.js", "/run-browser/style.css", "/runs", "/experiments", "/runs/ui/options"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                if (path.equals("/runs/ui")) {
                    assertTrue(response.headers().firstValue("content-type").orElseThrow().contains("text/html"));
                    assertTrue(response.body().contains("/run-browser/app.js"));
                }
                if (path.equals("/runs/ui/options")) {
                    var options = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
                    assertEquals(2, options.get("pageSize").asInt());
                    assertEquals(4096, options.get("artifactDownloadMaxBytes").asLong());
                }
                if (path.equals("/runs")) assertTrue(response.body().contains("\"runs\""));
            }
        }
    }
}
