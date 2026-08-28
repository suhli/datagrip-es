# Elasticsearch REST for DataGrip

An installable DataGrip data source backed entirely by Elasticsearch's standard
REST API. It does not use Elasticsearch SQL (`/_sql`), Elastic's JDBC/ODBC
drivers, or subscription-only features.

## Modules

- `jdbc`: standalone Elasticsearch REST to JDBC facade with no JetBrains API
  dependency.
- `plugin`: thin DataGrip integration that bundles and registers the JDBC
  driver.
- `integration-test`: optional tests against a local Elasticsearch Basic
  container or an externally supplied test cluster.

## Build

Requirements: JDK 21. The standalone JDBC artifact targets Java 17.

```shell
./gradlew test :plugin:buildPlugin
```

Artifacts:

- `jdbc/build/libs/elasticsearch-rest-jdbc.jar`
- `plugin/build/distributions/datagrip-elasticsearch-rest-*.zip`

Install the plugin ZIP with **Settings | Plugins | Install Plugin from Disk**,
then create **New Data Source | Other | Elasticsearch REST**.

## JDBC URL

```text
jdbc:es-rest://localhost:9200
jdbc:es-rest://localhost:9200/elasticsearch?ssl=true&verifyTls=false
jdbc:es-rest:https://example.com:9200/elasticsearch
jdbc:es-rest://[::1]:9200
```

Connection properties override URL query parameters:

- `ssl`: use HTTPS (`false` by default).
- `verifyTls`: verify certificate chain and hostname (`true` by default).
- `pathPrefix`: reverse-proxy prefix such as `/elasticsearch`.
- `connectTimeout`: connection timeout in milliseconds.
- `requestTimeout`: request timeout in milliseconds.
- `auth`: `none`, `basic`, or `apiKey`.
- `user` / `password`: Basic credentials.
- `apiKey`: API Key value for standalone JDBC callers.
- `header.<name>`: a custom HTTP header.

Credentials should be supplied through `Properties`, not the URL. They are
excluded from logging and exception messages.

### Authentication in DataGrip

For Basic authentication, use DataGrip's standard User and Password fields.
For API Key authentication, set the advanced driver property `auth=apiKey` and
store the key in the Password field. This deliberately reuses DataGrip's
PasswordSafe-backed secret field instead of persisting an API key as a normal
driver property.

## Query console

The driver accepts Elasticsearch Dev Tools-style requests through JDBC:

```http
GET /my-index/_search
{
  "size": 10,
  "query": {
    "match_all": {}
  }
}
```

In the generic DataGrip JDBC console, select the complete request (request line
and body) before executing it. DataGrip then passes the selected text to
`Statement.execute`, and the normal Result Grid, copy, and export actions are
available. One REST request per execution is supported in this release.

Search hits become rows containing `_index`, `_id`, `_score`, and flattened
`_source` fields. Typical aggregation buckets, JSON objects, and arrays are
also tabularized; responses that cannot be flattened safely remain available
without loss in a `JSON` column.

## TLS security

`verifyTls=true` is the default.

`verifyTls=false` is insecure and intended for trusted/private environments.
It disables both X.509 certificate-chain validation and hostname verification
only for the HTTP client owned by that JDBC connection. The driver never
changes the JVM default `SSLContext`, `HostnameVerifier`, or DataGrip trust
configuration.

## Metadata mapping

- Elasticsearch cluster → JDBC catalog
- index → JDBC table
- mapping field → JDBC column

Object and nested fields are flattened with dot notation. Multi-fields are
retained, for example `name` and `name.keyword`. The original Elasticsearch
type is preserved in JDBC metadata even when a non-standard type must map to
`OTHER`, `JAVA_OBJECT`, or a string type.

## Optional integration test

Start a Basic-licensed single-node cluster:

```shell
docker compose -f integration-test/docker-compose.yml up -d --wait
ES_TEST_URL=http://localhost:19200 ./gradlew :integration-test:test
```

Set `ES_VERSION` to exercise another supported 7.x, 8.x, or 9.x image. The
ordinary unit test suite never requires Docker or a public Elasticsearch
instance.

## Compatibility and limits

- DataGrip 2025.1 and newer.
- Elasticsearch 7.x, 8.x, and 9.x.
- OpenSearch is detected and may work for naturally compatible APIs, but is
  not reported as official Elasticsearch.
- No automatic search pagination, SQL translation, DDL, inline data editing,
  or multiple JDBC result sets.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency licenses.
