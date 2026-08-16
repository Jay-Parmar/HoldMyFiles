import "./styles.css";
import { renderShell } from "./shell";

const root = document.querySelector<HTMLElement>("#app");
if (root === null) {
  throw new Error("Guest application root is missing");
}

renderShell(root);
