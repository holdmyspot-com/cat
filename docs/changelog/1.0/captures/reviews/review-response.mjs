/**
 * Indicates whether a CAT response is an approval or has complete findings and local file links.
 * The shipped contract indents finding fields with a tab. Historical captures use two spaces.
 */
export function validReviewResponse(response)
{
  if (typeof response !== "string")
    return false;
  if (response.trim() === "APPROVED")
    return true;
  const lines = response.split("\n");
  if (lines.find((line) => line.trim()) !== "## Findings")
    return false;
  const findings = lines.flatMap((line, index) => /^- \*\*/.test(line) ? [index] : []);
  if (findings.length === 0)
    return false;
  if (/\[[^\]]+\.(?:java|md|yaml)(?::\d+(?:-\d+)?)?\](?!\()|\[[^\]]+\.(?:java|md|yaml)(?::\d+(?:-\d+)?)?\]\(\s/m.test(response))
    return false;
  return findings.every((start, index) =>
  {
    if (!/^- \*\*\[[^\]]+\]\([^\s)]+\)/.test(lines[start]))
      return false;
    const finding = lines.slice(start, findings[index + 1]).join("\n");
    return ["Evidence", "Consequence", "Correction"].every((field) =>
      new RegExp(`^(?:\\t|  )- \\*\\*${field}:\\*\\*`, "m").test(finding));
  });
}
