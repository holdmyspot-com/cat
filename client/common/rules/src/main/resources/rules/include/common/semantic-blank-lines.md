# Semantic Blank Lines

## Design Goals

- Let readers scan source and configuration and identify meaningful stages or groups.

## Guidance

Use a blank line to separate independently nameable stages or groups. In a method, these can be setup, an action,
verification, cleanup, or error handling. In configuration, they can be groups with different purposes.

Do not add blank lines merely to make short chunks. Keep a cohesive calculation, assertion sequence, mapping, list, or
related property group together. A blank line must show that the reader is now looking at work with a different purpose.

In a multi-step calculation, assign a non-trivial intermediate result to a clearly named variable before using it in a
following calculation. A conditional choice is non-trivial; do not hide it inside a larger arithmetic expression when
naming the selected value makes the stages easier to read.

In a delimited declaration or list, omit the separator after the final element unless the target language grammar
requires it.
