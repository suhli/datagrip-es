package io.github.suhli.datagrip.elasticsearch.plugin;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BundledDriverConfigurationTest {
    @Test
    void bundledJdbcArtifactIsAttachedToDriverDefinition() throws Exception {
        Document driver = resource("/databaseDrivers/elasticsearch-rest.xml");
        Document artifacts = resource("/databaseDrivers/elasticsearch-rest-artifacts.xml");

        var artifactRef = driver.getElementsByTagName("artifact").item(0).getAttributes();
        assertEquals("elasticsearch.rest.bundled", artifactRef.getNamedItem("name").getNodeValue());
        assertEquals("true", artifactRef.getNamedItem("use").getNodeValue());
        assertEquals("true", artifactRef.getNamedItem("rolling").getNodeValue());

        var artifact = artifacts.getElementsByTagName("artifact").item(0).getAttributes();
        assertEquals("elasticsearch.rest.bundled", artifact.getNamedItem("id").getNodeValue());
        assertEquals(
                "0.1.0",
                artifacts.getElementsByTagName("version").item(0)
                        .getAttributes().getNamedItem("version").getNodeValue());
        String url = artifacts.getElementsByTagName("item").item(0)
                .getAttributes().getNamedItem("url").getNodeValue();
        assertEquals(
                "file://$APPLICATION_PLUGINS_DIR$/datagrip-elasticsearch-rest-plugin/lib/"
                        + "elasticsearch-rest-jdbc.jar",
                url);
    }

    private static Document resource(String path) throws Exception {
        try (InputStream stream = BundledDriverConfigurationTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
        }
    }
}
