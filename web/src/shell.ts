import { GuestApiError } from "./api";
import type { GuestListing, GuestNode } from "./api";

interface GuestApi {
  login(pin: string): Promise<void>;
  roots(): Promise<GuestListing>;
  list(handle: string): Promise<GuestListing>;
}

interface BrowserFrame {
  readonly name: string;
  readonly handle: string | null;
}

const ROOT_FRAME: BrowserFrame = { name: "Shared files", handle: null };

export function mountGuestApp(root: HTMLElement, api: GuestApi): void {
  renderLogin(root, api);
}

function renderLogin(root: HTMLElement, api: GuestApi, initialError?: string): void {
  const section = document.createElement("section");
  section.className = "card";
  section.setAttribute("aria-labelledby", "login-title");

  const brand = document.createElement("p");
  brand.className = "eyebrow";
  brand.textContent = "Local file sharing";

  const heading = document.createElement("h1");
  heading.id = "login-title";
  heading.textContent = "Hold My Files";

  const introduction = document.createElement("p");
  introduction.textContent = "Enter the PIN shown on the sharing phone.";

  const form = document.createElement("form");
  form.noValidate = true;

  const label = document.createElement("label");
  label.htmlFor = "pin";
  label.textContent = "Six digit PIN";

  const input = document.createElement("input");
  input.id = "pin";
  input.name = "pin";
  input.type = "text";
  input.inputMode = "numeric";
  input.pattern = "[0-9]{6}";
  input.maxLength = 6;
  input.autocomplete = "one-time-code";
  input.spellcheck = false;
  input.required = true;
  input.setAttribute("aria-describedby", "pin-help");

  const help = document.createElement("p");
  help.id = "pin-help";
  help.className = "help";
  help.textContent = "Ask the phone owner for the current PIN.";

  const submitButton = document.createElement("button");
  submitButton.type = "submit";
  submitButton.textContent = "Open shared files";

  const status = document.createElement("p");
  status.className = "status";
  status.setAttribute("role", "status");
  status.hidden = true;

  const error = document.createElement("p");
  error.className = "error";
  error.setAttribute("role", "alert");
  error.hidden = true;

  form.append(label, input, help, submitButton, status, error);
  section.append(brand, heading, introduction, form);
  root.replaceChildren(section);
  input.focus();
  if (initialError !== undefined) {
    showError(error, initialError);
  }

  let submitting = false;
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    if (!/^\d{6}$/.test(input.value)) {
      showError(error, "Enter all six digits.");
      input.focus();
      return;
    }

    submitting = true;
    setBusy(input, submitButton, status, error, true);
    const pin = input.value;
    void api
      .login(pin)
      .then(() => {
        input.value = "";
        void loadFrame(root, api, [ROOT_FRAME]);
      })
      .catch((cause: unknown) => {
        input.value = "";
        showError(error, loginErrorMessage(cause));
        input.focus();
      })
      .finally(() => {
        submitting = false;
        if (form.isConnected) {
          setBusy(input, submitButton, status, error, false);
        }
      });
  });
}

async function loadFrame(
  root: HTMLElement,
  api: GuestApi,
  frames: readonly BrowserFrame[],
): Promise<void> {
  const frame = frames.at(-1);
  if (frame === undefined) {
    renderBrowseFailure(root, api, frames, new GuestApiError(502));
    return;
  }

  renderLoading(root, frame.name);
  try {
    const listing =
      frame.handle === null ? await api.roots() : await api.list(frame.handle);
    renderListing(root, api, frames, listing);
  } catch (cause: unknown) {
    renderBrowseFailure(root, api, frames, cause);
  }
}

function renderLoading(root: HTMLElement, title: string): void {
  const heading = document.createElement("h1");
  heading.tabIndex = -1;
  appendDirectionalText(heading, title);

  const status = document.createElement("p");
  status.setAttribute("role", "status");
  status.textContent = "Loading shared files.";

  root.replaceChildren(heading, status);
  heading.focus();
}

function renderListing(
  root: HTMLElement,
  api: GuestApi,
  frames: readonly BrowserFrame[],
  listing: GuestListing,
): void {
  const frame = frames.at(-1);
  if (frame === undefined) {
    renderBrowseFailure(root, api, frames, new GuestApiError(502));
    return;
  }

  const breadcrumbs = createBreadcrumbs(root, api, frames);
  const heading = document.createElement("h1");
  heading.tabIndex = -1;
  appendDirectionalText(heading, frame.name);

  const list = document.createElement("ul");
  list.className = "node-list";
  for (const node of listing.nodes) {
    list.append(createNodeRow(root, api, frames, node));
  }

  const content: Node[] = [breadcrumbs, heading];
  if (listing.nodes.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-state";
    empty.textContent = "Nothing is shared here yet.";
    content.push(empty);
  } else {
    content.push(list);
  }
  if (listing.truncated) {
    const notice = document.createElement("p");
    notice.className = "notice";
    notice.setAttribute("role", "status");
    notice.textContent = "Only the first 1,000 items are shown.";
    content.push(notice);
  }

  root.replaceChildren(...content);
  heading.focus();
}

function createBreadcrumbs(
  root: HTMLElement,
  api: GuestApi,
  frames: readonly BrowserFrame[],
): HTMLElement {
  const navigation = document.createElement("nav");
  navigation.setAttribute("aria-label", "Breadcrumb");
  const list = document.createElement("ol");
  list.className = "breadcrumbs";

  frames.forEach((frame, index) => {
    const item = document.createElement("li");
    if (index === frames.length - 1) {
      const current = document.createElement("span");
      current.setAttribute("aria-current", "page");
      appendDirectionalText(current, frame.name);
      item.append(current);
    } else {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "breadcrumb-button";
      appendDirectionalText(button, frame.name);
      button.addEventListener("click", () => {
        void loadFrame(root, api, frames.slice(0, index + 1));
      });
      item.append(button);
    }
    list.append(item);
  });

  navigation.append(list);
  return navigation;
}

function createNodeRow(
  root: HTMLElement,
  api: GuestApi,
  frames: readonly BrowserFrame[],
  node: GuestNode,
): HTMLElement {
  const item = document.createElement("li");
  item.className = "node-row";
  const label = document.createElement("bdi");
  label.dir = "auto";
  label.textContent = node.name;

  if (node.kind === "directory") {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "node-action node-directory";
    button.append(label);
    button.addEventListener("click", () => {
      void loadFrame(root, api, [
        ...frames,
        { name: node.name, handle: node.handle },
      ]);
    });
    item.append(button);
    return item;
  }

  const link = document.createElement("a");
  link.className = "node-action node-file";
  link.href = `/api/v1/files/${node.handle}`;
  link.download = "";
  link.append(label);
  if (node.sizeBytes !== null) {
    const size = document.createElement("span");
    size.className = "file-size";
    size.textContent = formatSize(node.sizeBytes);
    link.append(size);
  }
  item.append(link);
  return item;
}

function renderBrowseFailure(
  root: HTMLElement,
  api: GuestApi,
  frames: readonly BrowserFrame[],
  cause: unknown,
): void {
  if (cause instanceof GuestApiError && cause.status === 401) {
    renderLogin(root, api, "Your session ended. Enter the current PIN.");
    return;
  }

  const heading = document.createElement("h1");
  heading.tabIndex = -1;
  heading.textContent = "Could not load files";
  const error = document.createElement("p");
  error.setAttribute("role", "alert");
  error.className = "error";
  error.textContent = browseErrorMessage(cause);
  const retry = document.createElement("button");
  retry.type = "button";
  retry.textContent = "Try again";
  retry.addEventListener("click", () => {
    void loadFrame(root, api, frames.length === 0 ? [ROOT_FRAME] : frames);
  });

  root.replaceChildren(heading, error, retry);
  heading.focus();
}

function browseErrorMessage(cause: unknown): string {
  if (cause instanceof GuestApiError && cause.status === 404) {
    return "That item is no longer available.";
  }
  if (cause instanceof GuestApiError && cause.status === 503) {
    return "The sharing phone is busy. Try again shortly.";
  }
  return "Check your connection to the sharing phone and try again.";
}

function appendDirectionalText(parent: HTMLElement, value: string): void {
  const text = document.createElement("bdi");
  text.dir = "auto";
  text.textContent = value;
  parent.append(text);
}

function formatSize(sizeBytes: number): string {
  if (sizeBytes < 1_024) {
    return `${sizeBytes} B`;
  }
  if (sizeBytes < 1_024 * 1_024) {
    return `${(sizeBytes / 1_024).toFixed(1)} KB`;
  }
  if (sizeBytes < 1_024 * 1_024 * 1_024) {
    return `${(sizeBytes / (1_024 * 1_024)).toFixed(1)} MB`;
  }
  return `${(sizeBytes / (1_024 * 1_024 * 1_024)).toFixed(1)} GB`;
}

function setBusy(
  input: HTMLInputElement,
  button: HTMLButtonElement,
  status: HTMLElement,
  error: HTMLElement,
  busy: boolean,
): void {
  input.disabled = busy;
  button.disabled = busy;
  button.textContent = busy ? "Checking PIN" : "Open shared files";
  status.textContent = busy ? "Checking PIN." : "";
  status.hidden = !busy;
  if (busy) {
    error.hidden = true;
    error.textContent = "";
  }
}

function showError(element: HTMLElement, message: string): void {
  element.textContent = message;
  element.hidden = false;
}

function loginErrorMessage(cause: unknown): string {
  if (!(cause instanceof GuestApiError)) {
    return "Could not reach the sharing phone.";
  }
  if (cause.status === 401) {
    return "That PIN did not work.";
  }
  if (cause.status === 429) {
    return cause.retryAfterSeconds === undefined
      ? "Too many attempts. Try again later."
      : `Too many attempts. Try again in ${cause.retryAfterSeconds} seconds.`;
  }
  if (cause.status === 503) {
    return "The sharing phone is busy. Try again shortly.";
  }
  return "Could not reach the sharing phone.";
}
