export class GuestApiError extends Error {
  readonly status: number;
  readonly retryAfterSeconds: number | undefined;

  constructor(status: number, retryAfterSeconds?: number) {
    super(status === 0 ? "Request could not be sent." : "Request was not accepted.");
    this.name = "GuestApiError";
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export type GuestNodeKind = "directory" | "file";

export interface GuestNode {
  readonly handle: string;
  readonly name: string;
  readonly kind: GuestNodeKind;
  readonly sizeBytes: number | null;
}

export interface GuestListing {
  readonly nodes: readonly GuestNode[];
  readonly truncated: boolean;
}

export class HttpGuestApi {
  constructor(private readonly send: typeof fetch = window.fetch.bind(window)) {}

  async login(pin: string): Promise<void> {
    if (!/^\d{6}$/.test(pin)) {
      throw new GuestApiError(0);
    }

    await this.request("/api/v1/session", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ pin }),
    });
  }

  async roots(): Promise<GuestListing> {
    return this.loadListing("/api/v1/shares");
  }

  async list(handle: string): Promise<GuestListing> {
    if (!HANDLE_PATTERN.test(handle)) {
      throw new GuestApiError(0);
    }
    return this.loadListing(`/api/v1/nodes/${handle}`);
  }

  private async loadListing(path: string): Promise<GuestListing> {
    const response = await this.request(path, { method: "GET" });
    let value: unknown;
    try {
      value = await response.json();
    } catch {
      throw new GuestApiError(502);
    }
    return parseListing(value);
  }

  private async request(path: string, options: RequestInit): Promise<Response> {
    let response: Response;
    try {
      response = await this.send(path, {
        ...options,
        credentials: "same-origin",
        mode: "same-origin",
        cache: "no-store",
        redirect: "error",
      });
    } catch {
      throw new GuestApiError(0);
    }

    if (!response.ok) {
      throw new GuestApiError(
        response.status,
        parseRetryAfter(response.headers.get("Retry-After")),
      );
    }
    return response;
  }
}

function parseListing(value: unknown): GuestListing {
  if (!isRecord(value) || !Array.isArray(value.nodes) || typeof value.truncated !== "boolean") {
    throw new GuestApiError(502);
  }
  if (value.nodes.length > MAX_LISTING_NODES) {
    throw new GuestApiError(502);
  }

  return {
    nodes: value.nodes.map(parseNode),
    truncated: value.truncated,
  };
}

function parseNode(value: unknown): GuestNode {
  if (
    !isRecord(value) ||
    typeof value.handle !== "string" ||
    !HANDLE_PATTERN.test(value.handle) ||
    typeof value.name !== "string" ||
    value.name.length === 0 ||
    value.name.length > MAX_NAME_CHARS ||
    (value.kind !== "directory" && value.kind !== "file") ||
    !isValidSize(value.sizeBytes)
  ) {
    throw new GuestApiError(502);
  }

  return {
    handle: value.handle,
    name: value.name,
    kind: value.kind,
    sizeBytes: value.sizeBytes,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isValidSize(value: unknown): value is number | null {
  return (
    value === null ||
    (typeof value === "number" &&
      Number.isSafeInteger(value) &&
      value >= 0)
  );
}

function parseRetryAfter(value: string | null): number | undefined {
  if (value === null || !/^\d{1,5}$/.test(value)) {
    return undefined;
  }
  const seconds = Number(value);
  return seconds <= MAX_RETRY_AFTER_SECONDS ? seconds : undefined;
}

const MAX_RETRY_AFTER_SECONDS = 3_600;
const MAX_LISTING_NODES = 1_000;
const MAX_NAME_CHARS = 512;
const HANDLE_PATTERN = /^[A-Za-z0-9_-]{32}$/;
