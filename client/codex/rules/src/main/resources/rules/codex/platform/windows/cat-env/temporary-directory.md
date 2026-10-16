`CAT_TEMP` is `${CAT_PROJECT_ROOT}\.cat\work\temp`, and the directory exists. Wherever a rule or skill writes
`${CAT_TEMP}`, write that directory in its place; never expand `${CAT_TEMP}` from the environment. Create a temporary
directory there with
`(New-Item -ItemType Directory -Path "${CAT_TEMP}\<workflow-name>-$(Get-Random)").FullName`, which prints the new
directory's absolute path; use that output as the directory. Remove it with
`Remove-Item -LiteralPath <directory> -Recurse -Force` once nothing needs it, or move it with
`Move-Item -LiteralPath <directory> -Destination <new-path>` to hand it to a later owner.
