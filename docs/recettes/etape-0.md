# Acceptance test — Step 0: skeleton and exe

**Goal:** verify that the CI produces an installable, launchable `powerj.exe` with a minimal REPL.

## Getting the deliverables

1. Open the **Actions** tab of the GitHub repository, then the latest run of the **CI** workflow on the branch under test.
2. Under **Artifacts**, download:
   - `powerj-windows-x64-installer`: the `powerj-0.1.0.exe` installer;
   - `powerj-windows-x64-portable`: the portable version (zip containing `powerj\powerj.exe`).

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | Run the installer, accept the proposed choices (installation for the current user). | Installation completes without administrator rights. |
| 2 | Open **PowerJ** from the Start menu (*PowerJ* folder) or the desktop shortcut. | A console opens with the banner `PowerJ 0.1.0-SNAPSHOT (Java 27)` followed by the prompt `PJ C:\…> `. |
| 3 | Type `bonjour` then Enter. | Message `commande inconnue : bonjour`, then a new prompt. |
| 4 | Press Enter on an empty line. | A new prompt, with no message. |
| 5 | Type `exit trois`. | Message `exit : code retour invalide 'trois'`. |
| 6 | Type `exit`. | The window closes. |
| 7 | Portable version: unzip the archive, open `cmd.exe` in the `powerj` folder, type `powerj.exe`. | Banner and prompt showing the current folder of `cmd.exe`. |
| 8 | In PowerJ, type `exit 3`, then in `cmd.exe` type `echo %ERRORLEVEL%`. | Displays `3`. |

## Known limitations of step 0

- No history, arrow keys or Ctrl+R yet (step 1): input is a simple line read.
- The installer does not add PowerJ to the `PATH` yet: jpackage does not offer this natively; it will be handled in a later step (WiX customization). In the meantime, launch PowerJ from the Start menu or via its full path.
- No custom icon.
