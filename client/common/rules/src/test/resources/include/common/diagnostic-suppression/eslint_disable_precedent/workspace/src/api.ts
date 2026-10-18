export async function {{save_function}}(text: string): Promise<void> {
  const response = await fetch("/api/drafts", { method: "PUT", body: text });
  if (!response.ok) {
    throw new Error(`The server refused the draft: ${response.status}`);
  }
}

export async function refreshSession(): Promise<void> {
  await fetch("/api/session", { method: "POST" });
}

export async function recordView(page: string): Promise<void> {
  await fetch("/api/views", { method: "POST", body: page });
}
