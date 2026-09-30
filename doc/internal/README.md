# Internal documentation

This is the place for documents that record the thinking and specifications behind the code.
It is not documentation for users.

- [Time Travel and structured logging: implementation handoff](./design/2026-09-29-time-travel-logging-handoff.md)

As a policy, the format is kept light. Being able to keep it up takes priority.
Being able to record things in the first place takes priority over strict classification or inventory.

## What goes here

- The thinking, understanding of the specification, and reasons for decisions behind the code
- Current design guidelines and design-level thinking that spans multiple decisions
- Notes on things investigated or thought through, and proposals under consideration

Beyond that, there are basically no constraints on content: organizing notes or memos about the current specification, design or implementation are all fine.

## Difference between adr, design and notes

- `adr/`
  - Holds decisions that were adopted and decisions that were deliberately rejected.
  - A record for looking up "why it was done that way" later.
- `design/`
  - Holds current design guidelines and design-level thinking that spans multiple decisions.
- `notes/`
  - Holds things investigated, things thought through, organized specifications, proposals under consideration, and so on.
  - Open questions may remain.

Even something that should eventually be recorded as a decision or guideline goes into `notes/` while it is not yet settled.
When unsure how to classify something, putting it in `notes/` first is enough.
Once you want to record it formally, create a separate file in `adr/` or `design/`.
Not every `notes/` file has to be reorganized later.

## Operation

- One topic per file as the basic rule.
- For `adr/`, `design/` and `notes/` alike, file names are `YYYY-MM-DD-short-title.md`.
  - The `YYYY-MM-DD` in the file name is the creation date. It is not changed on update.
- Copy a template if needed.
  - For `adr/`: [`adr/template.md`](./adr/template.md)
  - For `design/`: [`design/template.md`](./design/template.md)
  - For `notes/`: [`notes/template.md`](./notes/template.md)

## Writing

- Keep the leading metadata to the minimum necessary.
  - For `adr/`, `design/` and `notes/` alike, include `Updated`.
    - When updating, it is desirable to make the changes identifiable as needed.

Headings are not fixed, but when in doubt use the following.

- `adr/`: `Background`, `Decision`, `Notes`
- `design/`: `Background`, `Policy`, `Notes`
- `notes/`: `Background`, `Current thinking`, `Open questions`
- If needed, put `Related` at the end to collect references to Issues, PRs, or other `adr/` / `design/` / `notes/` files.

In practice it is enough for `adr/` to have `Background` and `Decision` filled in, and for `design/` to have `Background` and `Policy`.
`notes/` only needs whichever items are necessary.
