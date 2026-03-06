# ProxyForge

ProxyForge is a Burp Suite Professional extension for managing and rotating upstream cloud-backed proxies from inside Burp. It provides a dedicated Montoya-powered Burp tab, a local routing proxy, provider panels for AWS Fireprox, Cloudflare Flareprox, and VPS-based proxies, plus a rotation engine that lets Burp traffic move through a managed proxy pool.

## Highlights

- Java 21 + Montoya API extension packaged as a single `ProxyForge.jar`
- Swing-based Burp tab with:
  - AWS Fireprox panel
  - Cloudflare Flareprox panel
  - VPS Forge panel
  - Proxy Pool table
  - Rotation Engine controls
  - Scope-based routing table
  - Embedded logging panel
- Local HTTP proxy listener on a configurable port (default `127.0.0.1:8081`)
- Rotation strategies:
  - Random
  - Round-Robin
  - Least-Used
  - Sticky-per-host
  - Per-scope rule
- Provider lifecycle actions:
  - Deploy
  - List
  - Delete
- Mock mode for every provider so the extension can be validated without live cloud credentials
- Automatic cleanup on Burp unload
- Persistence for settings, proxy pool, and scope rules via Montoya persistence

## Architecture

ProxyForge follows a control-plane/data-plane split:

1. Burp sends outbound traffic to a local proxy rule:
   - Destination host: `*.*`
   - Upstream proxy host: `127.0.0.1`
   - Upstream proxy port: `8081` by default
2. The local ProxyForge listener receives Burp traffic.
3. The rotation engine chooses an active proxy from the pool.
4. ProxyForge either:
   - forwards the traffic to a standard HTTP upstream proxy,
   - forwards the traffic to a SOCKS5 proxy, or
   - rewrites plain HTTP requests to a target-specific forwarder such as Fireprox or Flareprox.
5. Provider state, logs, request counts, validation state, and scope mappings are surfaced in the Burp tab.

### Components

- `burp.ProxyForgeExtension` - Burp entry point and lifecycle manager
- `proxyforge.ui.ProxyForgeTab` - Swing UI and user workflows
- `proxyforge.providers.ProviderRegistry` - provider adapters for AWS, Cloudflare, and VPS provisioning
- `proxyforge.proxy.LocalProxyServer` - local proxy listener and request forwarding
- `proxyforge.proxy.ProxyRotationEngine` - pool selection, stickiness, and scope routing
- `proxyforge.models.ProxyForgeModels` - persisted data model
- `proxyforge.utils.*` - logging, persistence, JSON, HTTP helpers

## Provider support

### AWS Fireprox

- Inputs:
  - Access Key
  - Secret Key
  - Session Token (optional)
  - Region
  - Target URL
- Real mode:
  - uses AWS SDK v2 API Gateway REST APIs
  - creates a root `ANY /` and greedy `ANY /{proxy+}` HTTP proxy integration
  - deploys to the `proxy` stage
- Mock mode:
  - generates a realistic API Gateway-style endpoint and adds it to the pool without touching AWS

### Cloudflare Flareprox

- Inputs:
  - API Token
  - Account ID
  - Workers Subdomain
  - Target URL
- Real mode:
  - uploads a Worker script through the Cloudflare Workers API
  - returns a `workers.dev` endpoint for the deployed script
- Mock mode:
  - creates a realistic `workers.dev` endpoint and adds it to the pool

### VPS Forge

- Inputs:
  - Vendor (`DigitalOcean`, `Linode`, `AWS EC2`)
  - API Token
  - Region
  - Instance Type
  - Optional AWS-specific infrastructure fields for EC2
- Real mode:
  - DigitalOcean and Linode use provider APIs with cloud-init to bootstrap a `tinyproxy` instance
  - AWS EC2 supports launch/terminate when the required infrastructure fields are provided
- Mock mode:
  - creates a realistic SOCKS5-style pool entry without touching the cloud provider

## Build

### Prerequisites

- Java 21
- Network access for Gradle dependency resolution

### Prebuilt downloads

You do not need to build ProxyForge locally if you only want the installable jar.

- **GitHub Releases**
  - Maintainers can publish a version tag such as `v1.0.1`.
  - The GitHub Actions release workflow will attach `ProxyForge.jar` to that release automatically.
  - End users can download the jar directly from the repository's **Releases** page.
- **GitHub Actions artifacts**
  - Every push, pull request, and manual workflow run uploads a `ProxyForge.jar` artifact.
  - Open the relevant run under the repository's **Actions** tab and download the `ProxyForge-<commit-sha>` artifact.
  - This is useful for testing branch builds before an official release is tagged.

### Commands

```bash
./gradlew clean fatJar test
```

Artifacts:

- Extension JAR: `build/libs/ProxyForge.jar`
- Plain JAR: `build/libs/ProxyForge-plain.jar`
- Test reports: `build/reports/tests/test/index.html`

## Load in Burp Suite Professional

You can either download a prebuilt `ProxyForge.jar` from **GitHub Releases / Actions artifacts** or build it locally.

1. If you want to build the project yourself:

   ```bash
   ./gradlew clean fatJar test
   ```

2. Open Burp Suite Professional.
3. Go to **Extensions**.
4. Add a new **Java** extension.
5. Select the downloaded `ProxyForge.jar` or the locally built `build/libs/ProxyForge.jar`.
6. Confirm the extension loads and the **ProxyForge** tab appears.

## Quick start

1. Open the **ProxyForge** tab.
2. Leave **Mock Mode** enabled in one provider panel.
3. Populate the minimum fields:
   - AWS: region + target URL
   - Cloudflare: target URL (+ workers subdomain if using real mode)
   - VPS: vendor + region + instance type
4. Click **Deploy**.
5. In **Rotation Engine**, keep the default local port `8081`.
6. Click **Start / Restart Proxy**.
7. In Burp, configure one upstream proxy rule:
   - Destination host: `*.*`
   - Proxy host: `127.0.0.1`
   - Proxy port: `8081`
8. Click **Validate All**.
9. Use **Rotate Now** to force the next candidate.

## Usage guide

### Provider workflow

- **Deploy**
  - Creates a real or mock provider endpoint and adds it to the proxy pool.
- **List**
  - Fetches matching remote provider objects and merges them into the local pool.
- **Delete Selected**
  - Removes the currently highlighted entry and triggers provider cleanup.

### Proxy Pool

The pool table displays:

- status
- provider
- proxy type
- endpoint
- creation time
- requests served
- last error

You can:

- enable or disable entries
- delete the selected entry
- refresh the table

### Rotation Engine

- **Random** - choose a healthy proxy at random
- **Round-Robin** - rotate sequentially
- **Least-Used** - choose the lowest `requestsServed`
- **Sticky-per-host** - keep the same proxy for repeat hosts
- **Per-scope rule** - honor scope table mappings before fallback

### Scope-based routing

Rules support:

- simple host names
- wildcard-like `*.example.com` matching
- regex mode
- fixed proxy assignment
- provider preference assignment

### Validation

`Validate All` performs:

- direct connectivity checks for HTTP/SOCKS pool entries
- HTTP reachability checks for forwarder endpoints
- mock-success results for mock-mode entries

## Persistence and secrets

- Settings, pool entries, provider form values, and scope rules persist across Burp restarts.
- Sensitive provider values are kept in memory by default.
- If **Persist provider secrets** is enabled, the current form fields are written through Burp's persistence layer as part of the extension state.
- Automatic cleanup runs when the extension unloads.

## Local proxy behavior

- The listener binds to `127.0.0.1` by default.
- Optional external binding is available for advanced lab setups.
- Standard HTTP upstream proxies support plain HTTP forwarding plus `CONNECT` tunneling.
- SOCKS5 entries support outbound plain HTTP forwarding and `CONNECT` tunneling.
- Target-specific forwarders are used for rewritten plain HTTP requests.

## Testing

The project includes a smoke test that:

- starts a mock upstream HTTP proxy,
- starts the local ProxyForge listener,
- sends a plain HTTP request through the ProxyForge port,
- verifies the request is forwarded correctly, and
- validates round-robin selection behavior.

Run:

```bash
./gradlew test
```

## Screenshots description

The following views are intended for BApp submission screenshots:

1. **Main tab overview**
   - shows the provider tabs on the left, the proxy pool on the right, and the rotation engine at the top right
2. **AWS panel in mock deployment mode**
   - shows a target URL, region, and a successful mock Fireprox deployment
3. **Proxy pool with multiple providers**
   - shows mixed AWS, Cloudflare, and VPS entries with request counters
4. **Scope rule editor and validation results**
   - shows host-pattern routing rules and updated health state after `Validate All`
5. **Logging panel**
   - shows provider lifecycle actions and proxy events

## BApp submission notes

Repository submission assets included here:

- `README.md`
- `bapp.json`
- `BappManifest.bmf`
- complete source code
- Gradle wrapper
- single-install JAR packaging via `ProxyForge.jar`

Recommended build command for reviewers:

```bash
./gradlew clean fatJar test
```

Maintainer release flow:

1. Push commits normally to generate downloadable Actions artifacts.
2. Create and push a version tag such as `v1.0.1`.
3. GitHub Actions will publish a GitHub Release and attach `ProxyForge.jar`.

## Project layout

```text
src/main/java/burp
src/main/java/proxyforge/ui
src/main/java/proxyforge/providers
src/main/java/proxyforge/proxy
src/main/java/proxyforge/models
src/main/java/proxyforge/utils
src/main/resources/worker
src/main/resources/templates
```