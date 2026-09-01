# Third-party notices

The standalone JDBC distribution includes:

- Apache HttpComponents Client 5 and transitive Apache HttpComponents
  dependencies, licensed under the Apache License 2.0.
- Jackson Databind, Jackson Core, and Jackson Annotations, licensed under the
  Apache License 2.0.
- SLF4J API, licensed under the MIT License.

The plugin also ships compact Elasticsearch completion metadata generated at
build time from the Apache-2.0
[elasticsearch-specification](https://github.com/elastic/elasticsearch-specification)
`schema.json`. The upstream specification itself is not redistributed; only a
derived completion subset is packaged.

Test-only dependencies are not distributed with the plugin:

- JUnit 5, Eclipse Public License 2.0.
- Bouncy Castle test utilities, MIT License.

No Elasticsearch JDBC, ODBC, X-Pack SQL, or Elasticsearch SQL API code is
included or used.
