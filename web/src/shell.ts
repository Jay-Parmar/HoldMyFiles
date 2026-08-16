import { GuestApiError } from "./api";

interface SessionApi {
  login(pin: string): Promise<void>;
}

export function mountGuestApp(root: HTMLElement, api: SessionApi): void {
  renderLogin(root, api);
}

function renderLogin(root: HTMLElement, api: SessionApi): void {
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
        renderAuthenticated(root);
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

function renderAuthenticated(root: HTMLElement): void {
  const heading = document.createElement("h1");
  heading.tabIndex = -1;
  heading.textContent = "Shared files";

  const status = document.createElement("p");
  status.setAttribute("role", "status");
  status.textContent = "Connected. Loading shares.";

  root.replaceChildren(heading, status);
  heading.focus();
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
