# ES REST Data Source

ES REST Data Source connects DataGrip to Elasticsearch through its REST API. It
provides a REST-backed data source and a Dev Tools-style `.esrest` query
console without using Elasticsearch SQL or Elasticsearch JDBC/ODBC drivers.

## Features

- Browse Elasticsearch indices and mapping fields as database metadata.
- Execute REST requests in a `.esrest` console with method, path, and JSON
  highlighting, formatting, and completion.
- Complete API paths, request parameters, request bodies, Query DSL,
  aggregations, and mapping fields from generated API metadata.
- Refresh completion metadata for indices, aliases, data streams, and mapping
  fields without blocking the IDE UI.
- Send JSON and NDJSON requests, including `_bulk`, `_msearch`, and
  `_msearch/template`.
- Use Basic authentication or API Key authentication with DataGrip's password
  storage.
- Configure TLS certificate and hostname verification per connection.

## Supported versions

- DataGrip 2025.1 and newer.
- Elasticsearch 7.x, 8.x, and 9.x.
- OpenSearch is detected and may work for naturally compatible APIs, but is not
  presented as officially supported Elasticsearch.

## Installation

### Install from disk

Download or build the plugin ZIP, then install it through:

**Settings → Plugins → ⚙ → Install Plugin from Disk...**

Create a data source through **New Data Source → Other → Elasticsearch REST**.
JetBrains Marketplace installation will be available after the initial
Marketplace release.

## Usage

The query console accepts Dev Tools-style requests:

```http
GET /my-index/_search
{
  "size": 10,
  "query": {
    "match_all": {}
  }
}
```

Execute the current request or select complete request blocks. Normal Result
Grid, copy, and export actions remain available. Multiple request blocks and
`//` or `#` line comments are supported.

Opening an index in the table data editor uses a local `SELECT` to REST
translator; it never calls Elasticsearch SQL. Text entered after the grid's
`WHERE` marker uses KQL semantics and is converted to Query DSL:

```text
status:200 AND (service.name:"checkout api" OR url.path:/orders/*)
```

Boolean operators, grouping, negation, comparisons, phrases, wildcards,
field-existence checks, free-text terms, and nested-field groups are supported.

## Connection properties

### JDBC URL

```text
jdbc:es-rest://localhost:9200
jdbc:es-rest://localhost:9200/elasticsearch?ssl=true&verifyTls=false
jdbc:es-rest:https://example.com:9200/elasticsearch
jdbc:es-rest://[::1]:9200
```

Connection properties override URL query parameters:

- `ssl`: use HTTPS (`false` by default).
- `verifyTls`: verify the certificate chain and hostname (`true` by default).
- `pathPrefix`: reverse-proxy prefix such as `/elasticsearch`.
- `connectTimeout`: connection timeout in milliseconds.
- `requestTimeout`: request timeout in milliseconds.
- `maxResponseBytes`: maximum decompressed HTTP response size in bytes
  (`67108864`, or 64 MiB, by default; `0` disables the limit).
- `auth`: `none`, `basic`, or `apiKey`.
- `user` / `password`: Basic credentials.
- `apiKey`: API Key value for standalone JDBC callers.
- `header.<name>`: a custom HTTP header.

Credentials should be supplied through connection properties, not the URL. They
are excluded from logging and exception messages.

### Authentication in DataGrip

For Basic authentication, use DataGrip's standard User and Password fields.
For API Key authentication, set the advanced driver property `auth=apiKey` and
store the key in the Password field. This uses DataGrip's PasswordSafe-backed
secret field instead of persisting an API key as a normal driver property.

## `.esrest` console and completion

REST Console files use the `.esrest` extension and support method/path/JSON
syntax highlighting and IDE code formatting. Completion covers endpoints,
query parameters, Query DSL and aggregation keys, mapping fields, and
data-driven snippets. It uses local schema resources and an immutable metadata
snapshot; mapping refresh runs in the background.

The following endpoints accept newline-delimited JSON and are sent with
`application/x-ndjson`: `_bulk`, `_msearch`, and `_msearch/template`.

```http
POST /_bulk
{"index":{"_index":"my-index"}}
{"title":"Example"}
```

Search hits become rows containing `_index`, `_id`, `_score`, and flattened
`_source` fields. Typical aggregation buckets, JSON objects, and arrays are
also tabularized. The complete response is exposed in `_response` on the first
row, and structured aggregations in `_aggregations` when present. Empty
responses with meaningful sections such as suggestions, profiles, PIT IDs,
timeouts, or shard failures retain their raw response.

## Security and TLS

`verifyTls=true` is the default. Setting `verifyTls=false` is insecure and is
intended only for trusted/private environments. It disables X.509
certificate-chain and hostname verification only for the HTTP client owned by
that JDBC connection; the driver does not change the JVM default `SSLContext`,
`HostnameVerifier`, or DataGrip trust configuration.

## Metadata mapping

- Elasticsearch cluster → JDBC catalog
- index → JDBC table
- mapping field → JDBC column

Object and nested fields are flattened with dot notation. Multi-fields are
retained, for example `name` and `name.keyword`. The original Elasticsearch
type is preserved in JDBC metadata even when it maps to `OTHER`,
`JAVA_OBJECT`, or a string type.

## Limitations

The plugin does not provide automatic search pagination, DDL, or inline data
editing. Multiple REST requests in one execution are available through JDBC
`getMoreResults()`.

## Build from source

Requirements: JDK 21. The standalone JDBC artifact targets Java 17.

```shell
./gradlew test :plugin:buildPlugin
```

Artifacts:

- `jdbc/build/libs/elasticsearch-rest-jdbc.jar`
- `plugin/build/distributions/es-rest-data-source-*.zip`

To run optional integration tests against a local cluster:

```shell
docker compose -f integration-test/docker-compose.yml up -d --wait
ES_TEST_URL=http://localhost:19200 ./gradlew :integration-test:test
```

Set `ES_VERSION` to exercise another supported 7.x, 8.x, or 9.x image. The
ordinary unit test suite never requires Docker or a public Elasticsearch
instance.

## Privacy

The plugin does not collect or transmit usage analytics or telemetry. Network
requests are made only to Elasticsearch endpoints configured by the user.

## Trademark notice

Elasticsearch is a trademark of Elasticsearch B.V. ES REST Data Source is an
independent open-source project and is not affiliated with or endorsed by
Elasticsearch B.V., Elastic, or JetBrains.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency licenses.

## License

Licensed under the [Apache License 2.0](LICENSE).
