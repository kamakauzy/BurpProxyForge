export default {
  async fetch(request) {
    const base = "__DEFAULT_TARGET__";
    const headers = new Headers(request.headers);
    if (!base || base.includes("__DEFAULT_TARGET__")) {
      const originalHost = headers.get("X-ProxyForge-Original-Host");
      const originalScheme = headers.get("X-ProxyForge-Original-Scheme") || "https";
      if (!originalHost) {
        return new Response("ProxyForge worker target not configured", { status: 500 });
      }

      const inbound = new URL(request.url);
      const target = new URL(`${originalScheme}://${originalHost}`);
      target.pathname = inbound.pathname;
      target.search = inbound.search;
      headers.set("Host", target.host);

      return fetch(target.toString(), {
        method: request.method,
        headers,
        body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
        redirect: "follow"
      });
    }

    const inbound = new URL(request.url);
    const target = new URL(base);
    target.pathname = `${target.pathname.replace(/\/$/, "")}${inbound.pathname}`;
    target.search = inbound.search;

    if (!headers.has("X-ProxyForge-Original-Host")) {
      headers.set("X-ProxyForge-Original-Host", inbound.host);
    }
    headers.set("Host", target.host);

    return fetch(target.toString(), {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
      redirect: "follow"
    });
  }
};
