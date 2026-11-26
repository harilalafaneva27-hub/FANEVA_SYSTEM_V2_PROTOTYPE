export function extractCloudflareErrorDiagnostics(raw, headers) {
  const title = raw.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]
    ?.replace(/<[^>]*>/g, " ").replace(/\\s+/g, " ").trim() ?? "(not found)";
  const cloudflareErrorCode = raw.match(/Error\\s+(\\d{3,4})/i)?.[1] ?? "(not exposed)";
  return {
    title,
    cloudflareErrorCode,
    rayId: headers?.get?.("cf-ray") ?? "(not exposed)",
    server: headers?.get?.("server") ?? "(not exposed)",
    bodyPreview: raw.slice(0, 500).replace(/\\s+/g, " ").trim() || "(empty)",
  };
}
