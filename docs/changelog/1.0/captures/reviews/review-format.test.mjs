import assert from "node:assert/strict";
import {mkdtemp, mkdir, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";

import {delegationPrompt, reviewFormat} from "./review-format.mjs";

const artifact = await mkdtemp(join(tmpdir(), "cat-review-format-"));
try
{
  const contract = "# Review Contract\n\nReturn exactly `APPROVED`.\n";
  await mkdir(join(artifact, "agents/common"), {recursive: true});
  await writeFile(join(artifact, "agents/common/review-contract.md"), contract);

  const loadedContract = await reviewFormat(artifact);

  assert.equal(loadedContract, contract.trim());
  assert.match(delegationPrompt("Review this change", loadedContract), new RegExp(contract.trim()));
}
finally
{
  await rm(artifact, {recursive: true, force: true});
}
