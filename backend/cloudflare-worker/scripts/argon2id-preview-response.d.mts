export interface CloudflareDiagnosticHeaders {
  get(name: string): string | null;
}
export interface CloudflareErrorDiagnostics {
  title: string;
  cloudflareErrorCode: string;
  rayId: string;
  server: string;
  bodyPreview: string;
}
export function extractCloudflareErrorDiagnostics(
  raw: string,
  headers?: CloudflareDiagnosticHeaders,
): CloudflareErrorDiagnostics;
