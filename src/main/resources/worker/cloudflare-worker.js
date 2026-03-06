export default {
  async fetch(request) {
    const base = "__DEFAULT_TARGET__";
    if (!base || base.includes("__DEFAULT_TARGET__")) {
      return new Response("ProxyForge worker target not configured", { status: 500 });
    }

    const inbound = new URL(request.url);
    const target = new URL(base);
    target.pathname = `${target.pathname.replace(/\/$/, "")}${inbound.pathname}`;
    target.search = inbound.search;

    const headers = new Headers(request.headers);
    headers.set("X-ProxyForge-Original-Host", inbound.host);
    headers.set("Host", target.host);

    return fetch(target.toString(), {
      method: request.method,
      headers,
      body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
      redirect: "follow"
    });
  }
};
