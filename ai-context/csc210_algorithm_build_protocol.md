---
name: csc210-algorithm-build-protocol
description: "MANDATORY working protocol for building the CSC210 algorithm module — propose/review/approve/write pair-programming cycle, Garv must understand every line before it is written to disk"
metadata:
  type: feedback
---

# Build Protocol — CSC210 Algorithm Module

**Status: binding.** This governs every coding session on the algorithm module
(see [[csc210_algorithm_build_order]]). Set by Garv 2026-08-23. Read this file
before writing any code for this project.

## Why this protocol exists

**Why:** Garv is graded on this code and will be questioned on it live by a TA
and professor. Code he did not read line-by-line before it existed is code he
cannot defend, debug, or extend. A working program he cannot explain is worth
less to him than a half-finished one he wrote with full understanding. He is
also learning the language and the algorithms at the same time — reading
finished code teaches far less than approving it before it exists.

**How to apply:** never trade his understanding for speed. If a session
produces two fully-understood methods instead of a finished class, that is a
successful session.

## The cycle

Every step in [[csc210_algorithm_build_order]] runs through six stages, in
order. Never skip, never merge, never run ahead.

### 1. PROPOSE — in chat, no files touched

Before a single file is created or edited, post in chat:

1. **What we are building and why it comes now.** One short paragraph. Name the
   concept this step teaches and what it unlocks in the next step.
2. **The decisions, in plain language.** Every non-obvious choice gets a
   `why this, not that` line. Not "we use a HashMap" — "we use a HashMap so
   looking up a node by its string id is one step instead of scanning the whole
   list; the alternative, scanning, would turn every edge insert into a walk
   over all nodes."
3. **Jargon glossed at first use.** Any term Garv has not already used himself
   in this project gets a one-line plain-English definition the first time it
   appears. Amortised, invariant, admissible, ADT, generic, decrease-key,
   adjacency list, boxing, JIT — all of these get glossed. Do not assume a term
   is known because it appeared in an earlier doc.
4. **The complete code.** Every line of every file, exactly as it will be
   written to disk. Full method bodies, full imports, full class declarations.
5. **A line-by-line or block-by-block walkthrough**, in the same message,
   covering what each part does and why it is written that way. Syntax that is
   new to Garv gets explained as syntax, not just as intent.
6. **How we will know it works** — the specific command to run and the exact
   output that means success.

**Hard rules for PROPOSE:**

- **No file writes.** Not "I'll draft it and you review the file." Code appears
  in chat first, always.
- **No placeholders. Ever.** No `// ... rest of the method`, no `// unchanged`,
  no `TODO: implement`, no truncated files. If the code is long, that is fine —
  post it in full. If it is too long to post in full, the step is too big:
  split it.
- **One concept per step.** Do not batch two build-order steps into one
  proposal because they are "both small". The point is one idea at a time.
- **No unrequested extras.** No bonus abstraction layer, no interface "for
  later", no extra config knob, no defensive helper nobody asked for. Build the
  step as scoped. If something extra genuinely looks necessary, say so in one
  sentence and let Garv decide.
- **Flag anything uncertain honestly.** If a design choice is a coin-flip, say
  it is a coin-flip and give the trade-off. Do not manufacture false confidence
  — he will repeat these justifications to a TA who probes them.

### 2. REVIEW — Garv reads

Garv reads the proposal line by line and asks whatever he wants.

**Hard rules for REVIEW:**

- **Every question gets a straight answer.** "Why is this line here", "what
  does this symbol mean", "what breaks if I delete this" — answer plainly, no
  defensiveness, no "as mentioned above."
- **A question is not a rejection.** Do not rewrite the code because he asked
  what something does. Answer the question. Change code only when he asks for a
  change.
- **If he spots a real bug or a better idea, say so plainly and re-propose.**
  Do not defend a weak choice to look consistent.
- **Never pressure toward approval.** No "should I go ahead?" nudging. He
  approves when he is ready.

### 3. APPROVE — explicit, from Garv

Work proceeds only on a clear go-ahead from Garv. Silence is not approval. A
question is not approval. "Looks fine so far" on part of the code is not
approval of the rest.

### 4. WRITE — to disk, exactly as approved

- What lands on disk is **byte-identical** to what was approved, except for
  whitespace the formatter would fix anyway.
- **If a problem is discovered while writing — stop.** Do not silently fix it.
  Say what was found, propose the corrected version, get approval again.
- No opportunistic edits to files outside the step's scope.

### 5. VERIFY — run it, show the real output

- Run the command named in the proposal. Paste the actual output, not a summary
  of it.
- **If it fails, say it failed and show the error verbatim.** Never report
  success that did not happen. Then diagnose in plain language before fixing —
  the debugging reasoning is itself part of what Garv is learning.

### 6. RECORD — close the loop

- Update the **current stage** marker in [[csc210_algorithm_build_order]] so a
  future session does not re-teach finished material.
- Add a **"how to explain this to the prof"** block to the end of the step:
  3–5 sentences, in Garv's own register, that he could say out loud when asked
  what this code does and why it is built that way. Include the honest
  limitation or trade-off — examiners reward knowing the weakness.
- Commit (see below).

## Code style, for explainability

- **Comments explain WHY, not WHAT.** `i++` needs no comment. A tie-breaking
  rule, an off-by-one that is deliberate, or a formula lifted from the sandbox
  spec does need one.
- **Javadoc on every public method** — one line of purpose, plus `@param` /
  `@return` where the meaning is not obvious from the name.
- **Names spelled out.** `distanceSeconds`, not `d`. `adjacencyHead`, not `ah`.
  The exception is a conventional loop index `i`/`j` and the standard algorithm
  variables `u`/`v` for graph endpoints, which match every textbook he will
  cite.
- **The deep explanation belongs in chat and in the context docs, not buried in
  30-line comment blocks.** Source files should read like code a competent
  student wrote, not like a tutorial.

## Anti-patterns — do not do these

| Anti-pattern | Why it is banned |
|---|---|
| Writing files then explaining after | He reviews before it exists, not after |
| `// rest of the implementation unchanged` | He cannot read a line he was never shown |
| Batching three steps because "they're small" | Defeats one-concept-at-a-time |
| "This is standard, you don't need the details" | The details are the deliverable |
| Adding an interface/factory/config layer unasked | Scope creep he then has to defend |
| Reporting a green test that was never run | Falsifies the record |
| Silently fixing a flaw found mid-write | Puts unapproved code on disk |
| Restating a concept he already used correctly | Wastes the session; check the stage tracker |

## Git

- Commit at the end of each **completed and verified** step, not mid-step.
- Commit messages written normally (not caveman), Conventional Commits style,
  explaining the *why* in the body when it is not obvious from the subject.
- Garv is the author. Co-author trailer for Claude as usual.
- The commit history is deliberately a record of the build order — one step,
  one commit, in sequence. It is evidence of process if anyone asks.

See also [[csc210_algorithm_build_order]], [[csc210_java_decision]],
[[csc210_algorithm_module_writeup]], [[csc210_algorithm_architecture]],
[[csc210_sandbox_data_layer]].
