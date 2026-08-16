import { describe, expect, it, vi } from "vitest";
import { GuestApiError, HttpGuestApi } from "./api";

describe("guest API login", () => {
  it("posts a six digit PIN as a same-origin JSON request", async () => {
    const fetchStub = vi.fn<typeof fetch>(async () => new Response(null, { status: 204 }));
    const api = new HttpGuestApi(fetchStub);

    await api.login("000042");

    expect(fetchStub).toHaveBeenCalledOnce();
    expect(fetchStub).toHaveBeenCalledWith(
      "/api/v1/session",
      expect.objectContaining({
        method: "POST",
        credentials: "same-origin",
        mode: "same-origin",
        cache: "no-store",
        redirect: "error",
        body: '{"pin":"000042"}',
      }),
    );
    const options = fetchStub.mock.calls[0]?.[1];
    expect(new Headers(options?.headers).get("Content-Type")).toBe("application/json");
  });

  it("rejects malformed PINs before making a request", async () => {
    const fetchStub = vi.fn<typeof fetch>();
    const api = new HttpGuestApi(fetchStub);

    await expect(api.login("42")).rejects.toMatchObject({ status: 0 });
    await expect(api.login("12345a")).rejects.toBeInstanceOf(GuestApiError);
    expect(fetchStub).not.toHaveBeenCalled();
  });

  it("maps login failures without exposing response text", async () => {
    const fetchStub = vi.fn<typeof fetch>(async () =>
      new Response("provider failed at content://private/tree", {
        status: 401,
        headers: { "Retry-After": "30" },
      }),
    );
    const api = new HttpGuestApi(fetchStub);

    const failure = await api.login("000042").catch((cause: unknown) => cause);

    expect(failure).toBeInstanceOf(GuestApiError);
    expect(failure).toMatchObject({ status: 401, retryAfterSeconds: 30 });
    expect(String(failure)).not.toContain("content://private/tree");
    expect(String(failure)).not.toContain("000042");
  });
});
