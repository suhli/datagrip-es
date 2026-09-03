# JetBrains Marketplace release

This repository produces a signed plugin ZIP for Marketplace uploads. Do not
modify the signed ZIP after Gradle creates it.

## First release

The first Marketplace publication must be uploaded manually.

1. Confirm `pluginVersion` is `0.1.0` in `gradle.properties` and that
   `CHANGELOG.md` has the matching version section.
2. Run the normal CI checks: tests, `verifyPlugin`, and `buildPlugin`.
3. Review Plugin Verifier output for plugin descriptor, dependency,
   compatibility, and internal API issues.
4. Add these GitHub Actions secrets: `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, and
   `PRIVATE_KEY_PASSWORD`. Add `PUBLISH_TOKEN` only for later Marketplace
   publishing.
5. Run the **Marketplace Release** workflow with
   `publish_to_marketplace` left disabled. It runs tests and verifier checks,
   builds and signs the plugin, verifies the signature, and uploads the signed
   ZIP as an Actions artifact.
6. Download the signed ZIP and test it locally with **Settings → Plugins → ⚙ →
   Install Plugin from Disk...**.
7. In JetBrains Marketplace, create or complete the Vendor Profile, accept the
   Developer Agreement, and manually upload the signed ZIP.
8. Select Apache License 2.0, set the source code URL to
   `https://github.com/suhli/datagrip-es`, add tags and real screenshots, make
   the required visibility decision, complete the Trader/Non-trader declaration,
   and submit for review.

### Marketplace checklist

- [ ] JetBrains Account
- [ ] Marketplace Developer Agreement accepted
- [ ] Vendor profile created
- [ ] Vendor website
- [ ] Vendor email
- [ ] Trader / Non-trader declaration
- [ ] Apache License 2.0 selected
- [ ] Source code URL entered
- [ ] Tags selected
- [ ] Screenshots added
- [ ] Plugin visibility / Hidden decision made
- [ ] Signed ZIP uploaded manually
- [ ] Confirm no Privacy Policy is required because the plugin does not collect personal or telemetry data

### Screenshot guidance

Capture real plugin screens only. Use a consistent 1200×760-or-larger frame and
the same IDE theme. Remove passwords, API keys, private hosts, internal domains,
IP addresses, and personal information before uploading.

1. Data source configuration showing ES REST Data Source, URL, authentication,
   and TLS options.
2. Database Explorer showing indices and mapping fields.
3. An `.esrest` console with completion visible, for example:

   ```http
   GET /products/_search
   {
     "query": {
       "term": {
         "category.keyword": "books"
       }
     }
   }
   ```

4. A Result Grid showing hits or an aggregation result.

## Later releases

1. Update `pluginVersion` in `gradle.properties` and add the matching
   `CHANGELOG.md` section.
2. Run CI and review Plugin Verifier results.
3. Run **Marketplace Release**. After the Marketplace listing has been created
   by the initial manual upload, enable `publish_to_marketplace` to run
   `publishPlugin`; this requires `PUBLISH_TOKEN` in addition to the signing
   secrets.
