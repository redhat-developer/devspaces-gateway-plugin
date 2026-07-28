# IDE HTTP proxy — verification plan

Honor JetBrains IDEA/Gateway **HTTP Proxy** settings for all plugin outbound HTTP (OkHttp/k8s, `java.net.HttpClient`, OIDC discovery).

Related helper: `com.redhat.devtools.gateway.util.IdeHttpProxy`.

---

## A. Automated checks

### Targeted unit tests

```bash
./gradlew test \
  --tests 'com.redhat.devtools.gateway.util.IdeHttpProxyTest' \
  --tests 'com.redhat.devtools.gateway.openshift.OpenShiftClientBuilderTest' \
  --tests 'com.redhat.devtools.gateway.auth.oidc.OidcProviderMetadataResolverTest' \
  --tests 'com.redhat.devtools.gateway.auth.code.*'
```

Or full suite: `./gradlew test`

### Static coverage sweep

Every outbound builder should go through `IdeHttpProxy`:

| Path | Expect |
|------|--------|
| `IdeHttpProxy.configure` | OIDC, OAuth, Sandbox, `BaseClientBuilder`, `LinkClientBuilder` |
| `HttpClient.newBuilder` | Only inside `IdeHttpProxy.configure(...)` |
| `OkHttpClient.Builder` | Inside `IdeHttpProxy.configure(...)` (Link also uses `existing.newBuilder()` then configure) |
| Nimbus `toHTTPRequest().send()` | **Absent** from `src/main` OIDC code |

```bash
rg -n 'toHTTPRequest|\.send\(\)' src/main/kotlin/com/redhat/devtools/gateway/auth/oidc/
rg -n 'HttpClient\.newBuilder|OkHttpClient\.Builder|IdeHttpProxy\.configure' src/main/kotlin/
```

---

## B. Manual proxy matrix (Gateway or IDEA)

**Settings → Appearance & Behavior → System Settings → HTTP Proxy**

Use a reachable Dev Spaces / SSO / cluster environment you already use for plugin testing.

| # | Proxy mode | What to exercise | Pass if |
|---|------------|------------------|---------|
| 1 | **No proxy** | OpenShift OAuth and/or RH SSO login, workspace list, connect | Same happy path as before |
| 2 | **Manual HTTP proxy** | Same flows | Requests succeed through proxy (proxy access logs, or fail clearly without a working proxy) |
| 3 | **PAC / auto-detect** (if available) | SSO discovery + API calls | Same success |
| 4 | **No-proxy hosts** | Add SSO + API hosts to exceptions | Direct connect; still works |
| 5 | **Proxy auth** | Proxy requiring credentials stored in IDE | Login + API succeed after IDE prompts/stores creds |

### Suggested flow per row

1. Start Gateway or IDEA with the plugin loaded.
2. Remote Development → OpenShift Dev Spaces connector.
3. Authenticate (OpenShift OAuth and/or Red Hat SSO as applicable).
4. List workspaces → Connect.

**Signals of proxy use:** proxy access log, or intentional wrong proxy → clear connection failure (then fix and retry).

### Paths covered by that flow

| Stack | Code |
|-------|------|
| OIDC metadata (RH SSO) | `OidcProviderMetadataResolver` → `HttpClient` |
| Token exchange / Sandbox | `RedHatAuthCodeFlow`, `SandboxApi` → `HttpClient` |
| OpenShift OAuth discovery | `OAuthDiscovery`, `OpenShiftAuthCodeFlow` → `HttpClient` |
| Cluster API (wizard) | `BaseClientBuilder` / `TokenClientBuilder` → OkHttp |
| Deep-link / kubeconfig | `LinkClientBuilder` → OkHttp via `newBuilder()` + `IdeHttpProxy` |

---

## C. Done when

- [ ] Targeted (or full) unit tests green
- [ ] Static sweep matches the table above
- [ ] Manual matrix rows **1–2** pass; **3–5** if the environment supports them
- [ ] No regression on the no-proxy path

---

## D. If something fails

| Symptom | Likely spot |
|---------|-------------|
| SSO discovery fails only with proxy | `OidcProviderMetadataResolver` / `IdeHttpProxy` (`HttpClient`) — must not use Nimbus `toHTTPRequest().send()` |
| `407 Proxy Authentication Required` on OAuth/OIDC | `sendAsyncWithProxy407Retry` / `enableBasicAuthForHttpProxyTunnelsOn407` — first 407 should enable Basic (if unset) and retry; explicit JVM props are preserved |
| Cluster API fails, SSO OK | `BaseClientBuilder` / `LinkClientBuilder` (OkHttp) |
| Sandbox signup fails | `SandboxApi` |
| OpenShift OAuth discovery fails | `OAuthDiscovery` / `OpenShiftAuthCodeFlow` |
| Works without auth proxy, fails with auth | `IdeProxyAuthenticator` / `JdkProxyProvider.ensureDefault` |
| Connection Failed + address must not be null | Still on `JAVA_NET_AUTHENTICATOR` — ensure OkHttp uses `IdeHttpProxy.PROXY_AUTHENTICATOR` |
