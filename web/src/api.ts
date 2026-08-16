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

export class HttpGuestApi {
  constructor(private readonly send: typeof fetch = window.fetch.bind(window)) {}

  async login(pin: string): Promise<void> {
    if (!/^\d{6}$/.test(pin)) {
      throw new GuestApiError(0);
    }

    let response: Response;
    try {
      response = await this.send("/api/v1/session", {
        method: "POST",
        credentials: "same-origin",
        mode: "same-origin",
        cache: "no-store",
        redirect: "error",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ pin }),
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
  }
}

function parseRetryAfter(value: string | null): number | undefined {
  if (value === null || !/^\d{1,5}$/.test(value)) {
    return undefined;
  }
  const seconds = Number(value);
  return seconds <= MAX_RETRY_AFTER_SECONDS ? seconds : undefined;
}

const MAX_RETRY_AFTER_SECONDS = 3_600;
