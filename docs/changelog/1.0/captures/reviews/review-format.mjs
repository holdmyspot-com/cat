// Reads the complete authoritative review contract from the packaged artifact that a capture executes.
import {readFile} from "node:fs/promises";
import {join} from "node:path";

export async function reviewFormat(artifact)
{
  return (await readFile(join(artifact, "agents/common/review-contract.md"), "utf8")).trim();
}

export function delegationPrompt(request, reviewContract)
{
  return `${request.trim()}\n\nFor the delegated child task, include this complete Review Contract verbatim:\n\n${reviewContract}\n`;
}
