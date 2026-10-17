CAT refused the result in `{{output_file}}` and the draft directory `{{draft_directory}}` because of these problems:

{{problems}}

Each problem starts with the file and line, or the JSON path of the result file, that it concerns, such as
`$.test.assertions[0]`. Correct every problem by editing only the files and values it concerns, and keep the rest
unless a correction requires changing it. Read only the parts of a file that you need. Then run
`{{launcher}} contract check {{context_file}}` and correct every problem that it prints. CAT reads the result only
from these files, so do not repeat it in your reply.
