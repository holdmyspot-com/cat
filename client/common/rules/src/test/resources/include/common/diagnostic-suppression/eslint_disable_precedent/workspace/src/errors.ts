export function reportError(error: unknown): void {
  const message = error instanceof Error ? error.message : String(error);
  document.querySelector("#status")!.textContent = message;
}
