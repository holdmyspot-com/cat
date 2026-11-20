import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const directory = new URL("./", import.meta.url);
const checkoutPrompt = await readFile(new URL("checkout-price-calculator.prompt.txt", directory), "utf8");
const documentationPrompt = await readFile(new URL("documentation.prompt.txt", directory), "utf8");

assert.equal(checkoutPrompt,
  "In [Language], implement a checkout-price calculator for one caller-supplied item price. The item price, discount rate, and tax rate are exact decimal strings in plain nonnegative decimal notation: digits with an optional decimal point, and no sign, exponent, whitespace, or separator. Discount and tax rates must be in the inclusive range [0, 1] and are decimal fractions, so \"0.1\" means 10%. Return the final total as an exact decimal string with exactly two decimal places, rounded using half-even rounding. The caller selects standard or expedited shipping. Apply the discount only to the item price. If expedited shipping is used, add a 15.00 fee after that discount and before tax.\n");
assert.doesNotMatch(checkoutPrompt, /CAT|rule|plugin|agent|library|dependency|representation|API|validation|comment/i,
  "the checkout prompt must state only caller-visible requirements");
assert.equal(documentationPrompt, "Document this code\n");
