import { describe, expect, it, vi } from "vitest";
import { GuestApiError } from "./api";
import type { GuestListing } from "./api";
import { mountGuestApp } from "./shell";

describe("guest login", () => {
  it("renders a six digit one-time-code form", () => {
    const root = document.createElement("main");

    mountGuestApp(root, api());

    const input = root.querySelector<HTMLInputElement>("#pin");
    expect(root.querySelector("h1")?.textContent).toBe("Hold My Files");
    expect(input?.type).toBe("text");
    expect(input?.inputMode).toBe("numeric");
    expect(input?.pattern).toBe("[0-9]{6}");
    expect(input?.maxLength).toBe(6);
    expect(input?.autocomplete).toBe("one-time-code");
    expect(root.querySelector("[role='alert']")).not.toBeNull();
  });

  it("rejects malformed input without calling the server", () => {
    const root = document.createElement("main");
    document.body.append(root);
    const login = vi.fn();
    mountGuestApp(root, api({ login }));
    const input = requiredInput(root);
    input.value = "42";

    submit(root);

    expect(login).not.toHaveBeenCalled();
    expect(root.querySelector("[role='alert']")?.textContent).toContain("six digits");
    expect(document.activeElement).toBe(input);
    root.remove();
  });

  it("preserves leading zeroes and clears the PIN after login", async () => {
    const root = document.createElement("main");
    document.body.append(root);
    const login = vi.fn(async () => undefined);
    mountGuestApp(root, api({ login }));
    const input = requiredInput(root);
    input.value = "000042";

    submit(root);

    await vi.waitFor(() => expect(login).toHaveBeenCalledWith("000042"));
    await vi.waitFor(() =>
      expect(root.querySelector("h1")?.textContent).toBe("Shared files"),
    );
    expect(root.textContent).not.toContain("000042");
    root.remove();
  });

  it("shows a fixed message when the PIN is rejected", async () => {
    const root = document.createElement("main");
    document.body.append(root);
    const login = vi.fn(async () => {
      throw new GuestApiError(401);
    });
    mountGuestApp(root, api({ login }));
    const input = requiredInput(root);
    input.value = "000042";

    submit(root);

    await vi.waitFor(() =>
      expect(root.querySelector("[role='alert']")?.textContent).toBe(
        "That PIN did not work.",
      ),
    );
    expect(root.textContent).not.toContain("000042");
    expect(document.activeElement).toBe(input);
    expect(input.disabled).toBe(false);
    root.remove();
  });

  it("blocks another submit while login is pending", async () => {
    const root = document.createElement("main");
    let finishLogin: (() => void) | undefined;
    const login = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishLogin = resolve;
        }),
    );
    mountGuestApp(root, api({ login }));
    const input = requiredInput(root);
    input.value = "123456";

    submit(root);
    submit(root);

    expect(login).toHaveBeenCalledTimes(1);
    finishLogin?.();
    await vi.waitFor(() =>
      expect(root.querySelector("h1")?.textContent).toBe("Shared files"),
    );
  });

  it("renders directories and direct file download links as text", async () => {
    const root = document.createElement("main");
    const dangerousName = "<img src=x onerror=alert(1)>";
    const roots = vi.fn(async () =>
      listing(
        {
          handle: "a".repeat(32),
          name: dangerousName,
          kind: "directory",
          sizeBytes: null,
        },
        {
          handle: "b".repeat(32),
          name: "notes.txt",
          kind: "file",
          sizeBytes: 12,
        },
      ),
    );
    mountGuestApp(root, api({ roots }));
    requiredInput(root).value = "123456";

    submit(root);

    await vi.waitFor(() => expect(root.querySelectorAll(".node-row")).toHaveLength(2));
    expect(root.querySelector("img")).toBeNull();
    expect(root.textContent).toContain(dangerousName);
    expect(root.querySelector("bdi")?.getAttribute("dir")).toBe("auto");
    const download = root.querySelector<HTMLAnchorElement>("a[download]");
    expect(download?.getAttribute("href")).toBe(`/api/v1/files/${"b".repeat(32)}`);
  });

  it("opens a directory and builds an in-memory breadcrumb", async () => {
    const root = document.createElement("main");
    const folderHandle = "c".repeat(32);
    const roots = vi.fn(async () =>
      listing({
        handle: folderHandle,
        name: "Photos",
        kind: "directory",
        sizeBytes: null,
      }),
    );
    const list = vi.fn(async () => listing());
    mountGuestApp(root, api({ roots, list }));
    requiredInput(root).value = "123456";
    submit(root);
    await vi.waitFor(() => expect(root.querySelector(".node-directory")).not.toBeNull());

    root.querySelector<HTMLButtonElement>(".node-directory")?.click();

    await vi.waitFor(() => expect(list).toHaveBeenCalledWith(folderHandle));
    await vi.waitFor(() => expect(root.querySelector("h1")?.textContent).toBe("Photos"));
    expect(root.querySelector("nav")?.getAttribute("aria-label")).toBe("Breadcrumb");
    expect(window.location.pathname).not.toContain(folderHandle);
  });

  it("announces truncated directories", async () => {
    const root = document.createElement("main");
    const roots = vi.fn(async () => ({ nodes: [], truncated: true }));
    mountGuestApp(root, api({ roots }));
    requiredInput(root).value = "123456";

    submit(root);

    await vi.waitFor(() =>
      expect(root.querySelector("[role='status']")?.textContent).toContain(
        "first 1,000 items",
      ),
    );
  });

  it("returns to login when the session expires", async () => {
    const root = document.createElement("main");
    const roots = vi.fn(async () => {
      throw new GuestApiError(401);
    });
    mountGuestApp(root, api({ roots }));
    requiredInput(root).value = "123456";

    submit(root);

    await vi.waitFor(() =>
      expect(root.querySelector("[role='alert']")?.textContent).toBe(
        "Your session ended. Enter the current PIN.",
      ),
    );
  });
});

interface ApiStub {
  login(pin: string): Promise<void>;
  roots(): Promise<GuestListing>;
  list(handle: string): Promise<GuestListing>;
}

function api(overrides: Partial<ApiStub> = {}): ApiStub {
  return {
    login: async () => undefined,
    roots: async () => listing(),
    list: async () => listing(),
    ...overrides,
  };
}

function listing(
  ...nodes: GuestListing["nodes"]
): GuestListing {
  return { nodes, truncated: false };
}

function requiredInput(root: HTMLElement): HTMLInputElement {
  const input = root.querySelector<HTMLInputElement>("#pin");
  if (input === null) {
    throw new Error("PIN input is missing");
  }
  return input;
}

function submit(root: HTMLElement): void {
  root.querySelector("form")?.dispatchEvent(
    new Event("submit", { bubbles: true, cancelable: true }),
  );
}
