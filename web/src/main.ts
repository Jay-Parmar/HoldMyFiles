import "./styles.css";
import { HttpGuestApi } from "./api";
import { mountGuestApp } from "./shell";

const root = document.querySelector<HTMLElement>("#app");
if (root === null) {
  throw new Error("Guest application root is missing");
}

mountGuestApp(root, new HttpGuestApi());
