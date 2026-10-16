# Plan — preview mode (mini-iteration 14, FR-66)

## Context
A "killer feature" discussed with the user: see the results of a pipeline while typing it. Decisions taken with the user:
- a **mode**, as in vi, rather than a prefix on the line: **Alt+V** ("View") switches between normal and preview mode (Ctrl+Esc ruled out: Windows Start menu, sent as Esc by most terminals);
- the mode is shown in the **prompt**: `PJ? <dir>> `;
- only the **cmdlets declared `preview = true`** (metadata of `@CmdletInfo`) and a **fixed set of JDK methods** run; a native command, a built-in command or "specific" Java code stops the preview. No editable whitelist, no question asked while typing;
- Tab completion in preview mode offers only what can run in a preview.

## Design
- `powerj-api`: `CmdletInfo.preview()` (default `false`); `Cmdlet.previewable(P)` (default `true`) for options that change something (`env --set`).
- `powerj-core`:
  - `Interpreter.preview(line, limit)` → `PreviewResult` (`Empty`, `Values`, `Off`, `Incomplete`, `Failed`): one statement only, no assignment, no redirection; runs the pipeline with a `PreviewGuard` bound, stops after `limit` values (`PreviewFull`), returns what arrived when interrupted.
  - `PreviewGuard`: `ScopedValue` bound to the evaluating thread, propagated to the pipeline stage threads and to the lambdas converted during the preview (`FunctionalAdapter`); parallel streams are outside the set (other threads); the values are formatted (`OutputFormatter`) under the guard too, so a lazy value (stream in a list) is checked; `deny(reason)` throws a `CancellationException` subclass so that no cmdlet or block swallows it. Checked in `Interpreter.prepare` (built-in, native, cmdlet without `preview`), `CmdletRunner` (`previewable`), `JavaInvoker` (methods, constructors), `PropertyAccess` (getters other than record accessors), `Evaluator` (`System.exit`, variables holding a stream or an iterator).
  - `PreviewPolicy`: the fixed set of JDK methods (`java.base` only).
  - `Completions.previewMode(boolean)`.
- `powerj-shell`:
  - `PreviewLineReader` (subclass of JLine's `LineReaderImpl`, built in `ShellReader` like `LineReaderBuilder` does): shows the preview in JLine's `post` area (below the line, after the completion list), tells the `Previewer` of each redisplay (line and cursor), clears the preview on accept and Ctrl+C.
  - `Previewer`: Alt+V widget, background worker (60 ms debounce, 400 ms deadline, cancellation by interrupt only while evaluating), rendering with `OutputFormatter`, banner.
  - `PreviewCut`: line cut at the stage under the cursor.
  - `Repl.prompt()`: `PJ?` in preview mode.

## Tests
- `PreviewTest` (core): previewable cmdlets and blocks, limit on an endless cmdlet, pure Java calls, formatted lines; cmdlet without `preview`, options (`previewable`), built-in, native, Java outside the set (also in a lambda, a constructor, a parallel stream, a lazy value displayed, a block converted before the preview), collection mutators, assignment/redirection/several commands, `System.exit`, stream variable, errors not displayed, interruption.
- `PreviewModeTest` (shell, in-memory terminal): Alt+V and prompt, results below the line, stage under the cursor, native command stopped and run on Enter, cmdlet table.
- `PreviewCutTest`, `CompletionsTest.previewModeOffersOnlyWhatCanRunInAPreview`.
- Acceptance checklist: `docs/recettes/preview.md`.
