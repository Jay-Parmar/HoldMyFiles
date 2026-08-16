import { describe, expect, it } from "vitest";
import { renderShell } from "./shell";

describe("guest shell", () => {
  it("renders the app name and connection instruction", () => {
    const root = document.createElement("main");

    renderShell(root);

    expect(root.querySelector("h1")?.textContent).toBe("Hold My Files");
    expect(root.textContent).toContain("PIN shown on the sharing phone");
  });
});
