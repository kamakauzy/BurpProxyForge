# ProxyForge

[![Build](https://github.com/kamakauzy/BurpProxyForge/actions/workflows/build-and-release.yml/badge.svg?branch=main)](https://github.com/kamakauzy/BurpProxyForge/actions/workflows/build-and-release.yml) [![Latest Release](https://img.shields.io/github/v/release/kamakauzy/BurpProxyForge?display_name=tag)](https://github.com/kamakauzy/BurpProxyForge/releases/latest) [![Downloads](https://img.shields.io/github/downloads/kamakauzy/BurpProxyForge/total)](https://github.com/kamakauzy/BurpProxyForge/releases)

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
- Provider actions create and manage real cloud resources using the supplied credentials
- Deploy, list, validate, and delete live provider endpoints directly from Burp
- Automatic cleanup on Burp unload
- Persistence for settings, proxy pool, and scope rules via Montoya persistence

## Architecture

ProxyForge now uses a hybrid routing architecture:

1. Burp sends outbound traffic to the local ProxyForge listener with one upstream proxy rule:
   - ProxyForge can manage this Burp project-level upstream proxy rule for you from inside the extension
   - Default managed target: `127.0.0.1:8081`
2. For VPS / HTTP / SOCKS proxy entries:
   - the local listener chooses a CONNECT-capable upstream proxy from the pool
   - Burp traffic is forwarded through that upstream proxy normally
3. For Fireprox / Flareprox forwarder entries:
   - ProxyForge rewrites matching requests inside Burp to the provider endpoint before they hit the local listener
   - the local listener detects those rewritten forwarder hosts and connects to them directly instead of re-rotating them
4. Provider state, logs, request counts, validation state, and scope mappings are surfaced in the Burp tab.

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
- Deployment:
  - uses AWS SDK v2 API Gateway REST APIs
  - creates a root `ANY /` and greedy `ANY /{proxy+}` HTTP proxy integration
  - deploys to the `proxy` stage
  - adds the deployed API Gateway endpoint to the proxy pool

### Cloudflare Flareprox

- Inputs:
  - API Token
  - Account ID
  - Workers Subdomain (enter only the account subdomain, not the full `workers.dev` URL)
  - Target URL (include the scheme and enter the real upstream application URL)
- Deployment:
  - uploads a Worker script through the Cloudflare Workers API
  - enables the public `workers.dev` route for the deployed script
  - verifies the public endpoint before adding it to the proxy pool
  - returns the verified `workers.dev` endpoint for the deployed script

### VPS Forge

- Inputs:
  - Vendor (`DigitalOcean`, `Linode`, `AWS EC2`)
  - API Token
  - Region
  - Instance Type
  - Optional AWS-specific infrastructure fields for EC2
- Deployment:
  - DigitalOcean and Linode use provider APIs with cloud-init to bootstrap a `tinyproxy` instance
  - AWS EC2 supports launch/terminate when the required infrastructure fields are provided
  - adds the provisioned proxy endpoint to the pool

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

## Versioning and changelog flow

ProxyForge uses a lightweight semver + GitHub Releases process.

- Release tags use the format `vMAJOR.MINOR.PATCH` such as `v1.0.1`.
- Tag builds pass the tag version into Gradle so the packaged jar manifest matches the GitHub Release version.
- GitHub Releases are the canonical changelog for published builds.
- GitHub autogenerated release notes are categorized by `.github/release.yml`.

Recommended PR labels:

- `breaking` -> major release
- `feature` -> minor release
- `bug` -> patch release
- `maintenance` -> patch release
- `docs` -> documentation-only or low-impact patch work
- `skip-changelog` -> exclude from release notes

Recommended maintainer release flow:

1. Merge labeled pull requests into the main branch.
2. Determine the next semantic version from the highest-impact merged label.
3. Create and push a tag such as `v1.0.1`.
4. GitHub Actions builds `ProxyForge.jar`, publishes a GitHub Release, and attaches the jar asset.

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
2. Enter valid provider credentials in one provider panel.
3. Populate the required deployment fields:
   - AWS: access key, secret key, region, target URL
   - Cloudflare: API token, account ID, workers subdomain, target URL
   - VPS: vendor, API token, region, instance type
4. Click **Deploy**.
5. In **Rotation Engine**, keep the default local port `8081`.
6. Click **Start / Restart Proxy**.
7. Enable **Burp upstream rule** if you want ProxyForge to add the Burp project-level rule automatically.
8. Use scope rules or target-host matching to decide whether a request should use:
   - a forwarder entry (Fireprox / Flareprox), or
   - a CONNECT-capable upstream proxy (VPS / HTTP / SOCKS)
9. Click **Validate All**.
10. Use **Rotate Now** to force the next candidate for the upstream proxy lane.

## Usage guide

### Provider workflow

- **Deploy**
  - Creates a provider endpoint and adds it to the proxy pool.
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

- **Random** - choose a healthy upstream proxy or forwarder candidate at random
- **Round-Robin** - rotate sequentially
- **Least-Used** - choose the lowest `requestsServed`
- **Sticky-per-host** - keep the same route for repeat hosts
- **Per-scope rule** - honor scope table mappings before fallback

### Scope-based routing

Rules support:

- simple host names
- wildcard host-pattern matching
- regex mode
- fixed proxy assignment
- provider preference assignment

Hybrid routing behavior:

- If a rule resolves to an AWS Fireprox or Cloudflare Flareprox entry, ProxyForge rewrites the request inside Burp to that forwarder endpoint.
- If a rule resolves to a VPS / HTTP / SOCKS entry, the local proxy forwards traffic through that upstream proxy.
- If no forwarder rule matches, ProxyForge falls back to the upstream proxy lane.

### Validation

`Validate All` performs:

- direct connectivity checks for HTTP/SOCKS pool entries
- HTTP reachability checks for forwarder endpoints
- live health results based on connectivity checks against deployed endpoints

## Persistence and secrets

- Settings, pool entries, provider form values, and scope rules persist across Burp restarts.
- Sensitive provider values are kept in memory by default.
- If **Persist provider secrets** is enabled, the current form fields are written through Burp's persistence layer as part of the extension state.
- If **Burp upstream rule** is enabled, ProxyForge manages the Burp project-level upstream rule and removes it when disabled or unloaded.
- Automatic cleanup runs when the extension unloads.

## Local proxy behavior

- The listener binds to `127.0.0.1` by default.
- Optional external binding is available for advanced lab setups.
- Standard HTTP upstream proxies support plain HTTP forwarding plus `CONNECT` tunneling.
- SOCKS5 entries support outbound plain HTTP forwarding and `CONNECT` tunneling.
- Fireprox and Flareprox entries are treated as forwarders, not generic upstream CONNECT proxies.
- Matching requests are rewritten inside Burp to the provider endpoint, and the local listener bypasses re-rotation for those rewritten forwarder hosts.

## Testing

The project includes an automated smoke test that:

- starts a local upstream HTTP proxy test fixture,
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
2. **AWS panel with a live Fireprox deployment**
   - shows a target URL, region, and the resulting deployed Fireprox endpoint
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
2. Label changes with `breaking`, `feature`, `bug`, `maintenance`, `docs`, or `skip-changelog`.
3. Create and push a semantic version tag such as `v1.0.1`.
4. GitHub Actions will build `ProxyForge.jar`, publish a GitHub Release, and attach the jar with categorized release notes.

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