# Repository Instructions

## Claude instructions take priority

- Before handling any task in this repository, read `.claude/CLAUDE.md` first and follow its instructions.
- Also read and follow any relevant files referenced by `.claude/CLAUDE.md`, including applicable commands, agents, and skills under `.claude/`.
- If these repository instructions conflict with `.claude/CLAUDE.md` or its referenced instructions, the Claude instructions take priority.

## Git autonomy

- When the user asks to implement, fix, or complete a task, make all necessary in-scope local changes and run relevant non-destructive validation without asking for confirmation.
- A request to modify the project does not implicitly authorize creating commits or pushing changes.
- If the user explicitly asks to commit, stage and commit the changes made for the requested task without asking again.
- If the user explicitly asks to push, push the resulting commit to the current branch without asking again.
- Do not repeatedly request verbal confirmation for an action the user has already explicitly authorized.
- Preserve unrelated user changes, and never use destructive Git commands unless the user explicitly requests them.
