# Contributing

You need Java 17, Maven 3.9+, Node 22+ and Docker with Compose v2.

```bash
make up            # build the images and start the stack
make help          # every other target
mvn test -Dtest='!*IntegrationTest,!*IT,!*RepositoryTest,!*ConcurrencyTest,!*RbacTest,!*IsolationTest'   # no Docker
mvn test -pl railhook-api -am -Dtest=TunnelServiceTest    # one class
make test-ui && (cd railhook-ui && npm run lint && npm run typecheck)
make ratchets version-check types-check docs-check        # the guards CI runs
```

- The test class name picks the CI job: `*IntegrationTest`, `*IT`, `*RepositoryTest`,
  `*ConcurrencyTest`, `*RbacTest` and `*IsolationTest` run with Docker, the rest without.
- Use `mvn test`, not `mvn verify`: JaCoCo thresholds on `verify` fail on a partial test run.
- After an API change, regenerate `openapi.yaml` with
  `mvn test -pl railhook-api -Dtest=OpenApiDriftIntegrationTest -Dopenapi.regenerate=true`,
  then `cd railhook-ui && npm run types:generate`.
- Versions change only through `make version-set VERSION=x.y.z`.
- Branch `feature/<name>` from `develop` and open the PR against `develop`, never `main`.
  `main` only takes `release/*` and `hotfix/*`, as merge commits.
- Commit prefixes: `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`.
- New behaviour comes with a test written first. Read [`CONTEXT.md`](CONTEXT.md) before naming anything.
- Report vulnerabilities privately, see [`SECURITY.md`](SECURITY.md).

## Adding an inbound provider

A provider is one class and one test. Nothing else in the codebase changes: the API, the UI's
provider list and the SDKs read the installed providers from `GET /api/v1/incoming-providers`.

1. Add a `@Component` implementing `InboundProvider` in
   `railhook-api/src/main/java/com/webhook/platform/api/service/ingress/provider/`:

   ```java
   @Component
   public class PaddleProvider implements InboundProvider {

       private static final String HEADER = "Paddle-Signature";

       @Override
       public String id() {
           return "PADDLE";
       }

       @Override
       public String displayName() {
           return "Paddle";
       }

       @Override
       public String signatureHeader() {
           return HEADER;
       }

       @Override
       public VerificationResult verify(String secret, byte[] body, HttpServletRequest request) {
           String header = request.getHeader(HEADER);
           if (header == null || header.isBlank()) {
               return VerificationResult.failure("Missing header: " + HEADER);
           }
           // parse the header, check the timestamp window, compare with MessageDigest.isEqual
           return VerificationResult.success(header);
       }

       @Override
       public String eventId(HttpServletRequest request, String body) {
           return JsonBodies.text(JsonBodies.parse(body), "event_id");
       }
   }
   ```

   - `id()` is stored on the source as `providerType`: uppercase, at most 50 characters, and
     never renamed once released.
   - `verify` gets the raw body bytes. Hash those, not a re-encoded string.
   - `success(replayKey)` takes the value that makes a request unique (usually the signature),
     so the same signed request is refused a second time.
   - `eventId` is the id the provider keeps across its own retries, used to deduplicate. Leave
     it out if the provider has none. Never use a header a proxy could set.
   - `handshake` is for providers that confirm URL ownership with a challenge (see `SlackProvider`).
   - A provider that signs the URL takes `@Value("${webhook.ingress-base-url:}")`, as
     `TwilioProvider` does, instead of trusting `Host`.

2. Add `PaddleProviderTest` next to the other provider tests: a valid signature, a mismatch, a
   missing header, an expired timestamp, and the event id. Build real signatures in the test
   from a known secret.

3. Optionally, add a guide at `railhook-docs/src/content/docs/guides/paddle-webhooks.mdx` and
   a row in the providers table on `incoming/verification.mdx`. Write it in English; a
   maintainer adds the Ukrainian page.

`InboundProviderRegistry` refuses to start on a duplicate or malformed id, and
`InboundProviderIsolationTest` fails if code outside the provider package branches on a
provider's id. If a provider needs a hook the interface lacks, add a default method to
`InboundProvider` rather than an `if` in `IngressService`.

## Adding an alert channel

A channel is one class, one test and its name in the two locale files. The API and the Alerts
form read the installed channels and their settings from `GET /api/v1/alert-channels`.

1. Add a `@Component` implementing `AlertChannelProvider` in
   `railhook-api/src/main/java/com/webhook/platform/api/service/alert/channel/`:

   ```java
   @Slf4j
   @Component
   @Order(80)
   @RequiredArgsConstructor
   public class TeamsChannel implements AlertChannelProvider {

       private static final ConfigSchema SCHEMA = ConfigSchema.of(
               ConfigProperty.text("url", "Workflow URL").required().secret().format(ConfigProperty.URI));

       private final AlertHttpClient http;

       @Override
       public String id() {
           return "TEAMS";
       }

       @Override
       public String displayName() {
           return "Microsoft Teams";
       }

       @Override
       public ConfigSchema configSchema() {
           return SCHEMA;
       }

       @Override
       public void fire(AlertRule rule, AlertEvent event, Incident incident, ChannelConfig config) {
           http.post(config.get("url"), Map.of("text", event.getTitle()));
           log.info("Teams notification sent for rule '{}'", rule.getName());
       }
   }
   ```

   - `id()` is stored on the rule as `channel`: uppercase, at most 20 characters, never renamed
     once released.
   - Each setting is a `ConfigProperty`. `secret()` stores it encrypted, keeps it out of every
     response and log, and includes it in key rotation. `format(ConfigProperty.URI)` puts the
     value through the same SSRF check as an endpoint URL. `oneOf(...)` makes it a choice.
   - Send through `AlertHttpClient`: it is the SSRF-guarded client. Never log the URL or the
     config, only the host.
   - `pages()` returns true for a channel that wakes a person: an `INFO` rule is then refused,
     and `resolve` is called when the incident clears. Key the page on the incident id, as
     `PagerDutyChannel` does, so the resolve closes the page the trigger opened.
   - `normalize` is for checks the schema cannot express, such as `EmailChannel` limiting
     recipients to verified members.

2. Add `TeamsChannelTest`: mock `AlertHttpClient`, call `fire` and assert on the body sent.

3. Add `alerts.channels.TEAMS` to `railhook-ui/src/i18n/locales/en.json` and `uk.json`, and
   optionally `alerts.channelFields.TEAMS.<setting>` and `<setting>Hint` to translate the
   setting's title and description. Without them the form shows the schema's English text.

An alert condition works the same way: implement `AlertCondition` in `service/alert/condition/`,
declare which of the rule's `thresholdValue`, `windowMinutes` and `endpointId` it reads in its
schema, and return a `Breach` from `assess` while the condition holds. The evaluator fires on the
crossing and resolves when `assess` returns empty. Its labels live under `alerts.types.<ID>`,
`alerts.condition.<ID>` and `alerts.conditionFields.<ID>`.

`AlertChannelRegistry` and `AlertConditionRegistry` refuse to start on a duplicate or malformed id.
