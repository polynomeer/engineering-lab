package com.pnomeer.lab.app.run;

import com.pnomeer.lab.EngineeringLabApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=EngineeringLabApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"lab.storage.root=${java.io.tmpdir}/lab-ui-tests-${random.uuid}", "lab.storage.page-limit=2", "lab.storage.preview-limit=2", "lab.artifacts.download.max-bytes=4096"})
class RunUiIntegrationTest {
    @LocalServerPort int port;
    @Test void servesRunApiAndOptionsButNoLongerTheRemovedScreens() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/runs", "/experiments", "/runs/ui/options"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), path);
                if (path.equals("/runs/ui/options")) {
                    var options = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.body());
                    assertEquals(2, options.get("pageSize").asInt());
                    assertEquals(4096, options.get("artifactDownloadMaxBytes").asLong());
                }
                if (path.equals("/runs")) assertTrue(response.body().contains("\"runs\""));
            }
            // Test classpath has no embedded dashboard, so old screen addresses have nowhere to redirect.
            for (String path : new String[]{"/", "/ui", "/runs/ui", "/run-browser/app.js", "/experiments/ui", "/ingest/ui", "/ingest/ui/db",
                    "/lab/io/ui", "/dashboard/export-settings.js"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(), HttpResponse.BodyHandlers.discarding());
                assertEquals(404, response.statusCode(), path);
            }
        }
    }
}
