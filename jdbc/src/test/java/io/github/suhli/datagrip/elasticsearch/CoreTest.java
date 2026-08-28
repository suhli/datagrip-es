package io.github.suhli.datagrip.elasticsearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesUrlOptionsIpv6AndDoesNotLeakSecrets() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("password", "top-secret");
        properties.setProperty("verifyTls", "false");
        EsJdbcUrl url = EsJdbcUrl.parse("jdbc:es-rest:https://[::1]:9443/proxy?connectTimeout=123", properties);

        assertEquals("https", url.endpoint().getScheme());
        assertEquals("::1", url.endpoint().getHost());
        assertEquals("/proxy", url.endpoint().getPath());
        assertEquals(123, url.connectTimeout().toMillis());
        assertFalse(url.verifyTls());
        assertFalse(url.toString().contains("top-secret"));
    }

    @Test
    void parsesExactlyOneRestRequest() throws Exception {
        var request = RestRequestParser.parse("POST /index/_search\n{\"query\":{\"match_all\":{}}}");
        assertEquals("POST", request.method());
        assertEquals("/index/_search", request.path());
        assertThrows(SQLException.class,
                () -> RestRequestParser.parse("GET /a\n{}\nGET /b\n{}"));
    }

    @Test
    void flattensMappingsAndMultiFields() throws Exception {
        var fields = MappingFlattener.flatten(JSON.readTree("""
                {"mappings":{"properties":{"title":{"type":"text","fields":{"keyword":{"type":"keyword"}}},
                "author":{"properties":{"name":{"type":"keyword"}}}}}}
                """));
        assertTrue(fields.stream().anyMatch(f -> f.name().equals("title.keyword") && f.multiField()));
        assertTrue(fields.stream().anyMatch(f -> f.name().equals("author.name") && f.jdbcType() == Types.VARCHAR));
    }

    @Test
    void mapsSearchHitUnionAndComplexFallback() throws Exception {
        TabularResult result = JsonResultMapper.map("""
                {"hits":{"hits":[
                  {"_id":"1","_source":{"name":"one","tags":["a","b"]}},
                  {"_id":"2","_source":{"count":2}}
                ]}}
                """);
        assertEquals(2, result.rows().size());
        assertTrue(result.columns().stream().anyMatch(c -> c.label().equals("name")));
        assertTrue(result.columns().stream().anyMatch(c -> c.label().equals("count")));
        int tags = java.util.stream.IntStream.range(0, result.columns().size())
                .filter(i -> result.columns().get(i).label().equals("tags")).findFirst().orElseThrow();
        assertEquals("[\"a\",\"b\"]", result.rows().get(0).get(tags));
    }
}
