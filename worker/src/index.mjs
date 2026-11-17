import { authenticateDevice, AuthenticationError } from "./auth.mjs";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store"
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}

function requestId(request) {
  const supplied = request.headers.get("x-request-id");
  return supplied && /^[A-Za-z0-9._:-]{1,100}$/.test(supplied)
    ? supplied
    : crypto.randomUUID();
}

export default {
  async fetch(request, env) {
    const id = requestId(request);
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ status: "ok", service: "faneva-v2-staging-worker", requestId: id });
    }

    if (request.method === "POST" && url.pathname === "/v1/auth/session") {
      try {
        const principal = await authenticateDevice(request, env.DB);
        return json({
          authenticated: true,
          principal: {
            tenantId: principal.tenantId,
            accountId: principal.accountId,
            deviceId: principal.deviceId,
            role: principal.role,
            assignedStoreIds: principal.assignedStoreIds
          },
          requestId: id
        });
      } catch (error) {
        if (error instanceof AuthenticationError) {
          return json({
            error: { code: "UNAUTHENTICATED", message: "Valid active device credentials are required.", retryable: false },
            requestId: id
          }, 401);
        }
        // Do not disclose SQL details, credentials, or internal stack traces.
        return json({
          error: { code: "INTERNAL_ERROR", message: "The request could not be completed.", retryable: true },
          requestId: id
        }, 500);
      }
    }

    return json({
      error: { code: "NOT_FOUND", message: "Route not found.", retryable: false },
      requestId: id
    }, 404);
  }
};
