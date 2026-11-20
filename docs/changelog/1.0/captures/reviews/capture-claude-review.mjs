#!/usr/bin/env node
// Retains a Claude Code CAT review with at most one correction of its response format.
import {captureCatReview} from "./capture-review.mjs";

await captureCatReview("claude", process.argv.slice(2));
