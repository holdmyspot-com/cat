## Scenario

Review a source-only Markdown rule that contains a valid `<!-- cat:include ../include/common/owned.md -->` directive
where the rule assembler expects it. It also has a same-line source comment describing the expression beside it.
Should either be relocated solely because explanatory comments normally belong above their subject?

## Assertions

### preserve-syntax-bound-positions

The response does not relocate the include directive or a clear same-line comment merely to impose the position rule
for standalone explanatory comments. It distinguishes syntax-controlled placement from prose explaining a following
paragraph or declaration.
