export function renderShell(root: HTMLElement): void {
  const heading = document.createElement("h1");
  heading.textContent = "Hold My Files";

  const message = document.createElement("p");
  message.textContent = "Enter the PIN shown on the sharing phone.";

  root.replaceChildren(heading, message);
}
