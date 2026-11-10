# 0012. The shell is case-sensitive

**Status:** accepted (decision of [#130](https://github.com/yp44/powerj/issues/130); the options and the booleans amended by
[#88](https://github.com/yp44/powerj/issues/88)). It completes
[0011](0011-every-command-reads-the-properties-of-an-object-through-the-engine.md), whose rule on the case of a property name
now covers every name.

## Context
PowerJ is a shell oriented to Java, and Java is case-sensitive: `Math.max` is not `math.max`, `RoundingMode.HALF_UP` is not
`RoundingMode.half_up`. The shell had grown another rule, one place at a time:

- the name of an option and its value (`--Property`, `perf --view STAGES`, `--delimiter TAB`), by `OptionBinder`;
- a unit (`2MB`, `5M`) and the literals of `where` (`where dir == TRUE`), by `Units` and `Where`;
- the constant of a Java enum (`RoundingMode.half_up`), by `JavaInvoker`;
- a setting of the environment or of the configuration (`POWERJ_LANG=FR`, `POWERJ_BACKGROUND_SH_FALLBACK=TRUE`);
- the names of the processes and of their users (`ps JAVA*`), and of the variables of the environment (`env`).

After 0011 the name of a property was exact and the name of an option was not: the same user typed `sort size` and
`--property size` and was held to two rules, and a script that worked on Linux because the case was tolerated broke on the day a
name was looked up by an exact table (the commands, the variables and the classes already were: `Jobs`, `$Name` and
`import java.util.list` have never worked).

Not everything that a user writes is a name. The file system of Windows and of macOS ignores the case of a file name, the
environment of Windows ignores the case of a variable, and the system tells a process `NOTEPAD.EXE` from `notepad.exe` no more
than a user does.

## Decision
**What a user writes is compared exactly: the name of a cmdlet or of a command, of a variable, of a class, of a method, of a
constant, of a property, of an option and of its value when it is an enum, a unit, a literal, the value of a setting.** A name
written in the wrong case is not understood, and the error gives the name to use when there is one (`unknown option --Property,
did you mean --property?`).

- **Options.** `--recurse` is not `--Recurse`, and a long option is written whole: `--rec` is not `--recurse`
  (*amended by [#88](https://github.com/yp44/powerj/issues/88): the first version of this record kept the abbreviation, in the
  same case; a script that wrote `--rec` broke when a cmdlet added `--recover`, so adding an option was a breaking change*).
  The suggestion of the error message is the one place that still compares without regard to case, and that still looks at the
  beginning of a name: it only helps to find the name to write (`--rec` → `did you mean --recurse?`).
- **Values.** A `boolean` is `true` or `false` and nothing else (*amended by #88: the first version also took `yes`, `no`, `oui`,
  `non`, `1` and `0`; the message already said "expects true or false"*); an enum is its **lower-case name**
  (`perf --view stages`), which is what the help, the completion and the messages show. The constants of a Java enum keep their Java name.
  The enum of an option cannot have two constants that differ only by their case (they would have the same lower-case name).
- **Units and literals.** `1mb`, `5m`, `250ms`, `true`, `false`, `null`; `1MB` is an error that lists the units.
- **Settings.** `POWERJ_LANG=fr`, `POWERJ_GLOB_IGNORE_CASE=true`, `POWERJ_BACKGROUND_SH_FALLBACK=true`,
  `POWERJ_NATIVE_ENCODING=auto`: a value that is spelled in another case is not the value. `POWERJ_GLOB_IGNORE_CASE=TRUE` is an
  error that names the variable and the value; `POWERJ_BACKGROUND_SH_FALLBACK=TRUE` does not allow the fallback, and the refusal
  says to set `true`; `POWERJ_LANG=FR` is not a language, and any other value gives English (FR-61); `POWERJ_NATIVE_ENCODING=AUTO`
  is read as the name of a charset, and is refused as one.

**Four things are not names, and keep their rule.** They are the only exceptions; each is one family, and each class that
belongs to one is in the list of `CaseSensitivityTest` with its reason.

1. **What the system decides.** The names of files on Windows and macOS, the variables of the environment and the processes on
   Windows: PowerJ follows the system, it does not add a rule of its own (`Host.windows()`, `Host.namesIgnoreCase()`). On Linux and
   macOS the name of a process and of its user are exact; on Windows they are not (`ps notepad` finds `NOTEPAD.EXE`). A word that
   is neither a cmdlet nor a built-in is looked up as a **program**, by the file system: where it ignores case, `Jobs` is not an
   unknown command but the program `jobs` (`/usr/bin/jobs` on macOS), and `TRUE` is `true` (`/usr/bin/true`, `true.exe`). The shell
   still tells `jobs` from `Jobs`; the system finds a program for both.
2. **What the user asks for.** `sort -i`, `grep -i`, `range -i`, `POWERJ_GLOB_IGNORE_CASE=true`; and, in the window of the grid, the
   box of the filter ("contains", regular expressions) and the order of the texts, which a person reads in a window and does not
   type as a name.
3. **An encoding, or a rule of the JDK, not a name of PowerJ.** The digits of a hexadecimal fingerprint (`sha256:ABC…` is
   `sha256:abc…`), the attribute `Enable-Native-Access` of a manifest, which is read as the JDK reads a boolean of a manifest, and
   the name of a charset (`utf-8` is `UTF-8`), which `Charset.forName` reads.
4. **An answer, not a name.** `Y` answers a question as `y` does.

The sort that shows a folder (`ls`, a wildcard) puts the names in the order of a reader, whatever their case, and compares nothing:
it is an order, not a match.

**Enforced.** `CaseSensitivityTest` (module `powerj-architecture`) reads the sources of production, of every module, and fails
when `equalsIgnoreCase`, `compareToIgnoreCase`, `Pattern.CASE_INSENSITIVE`, `Pattern.UNICODE_CASE` or `regionMatches(true, …)`
appears in a class that is not in its list. A new exception is added to the list with its reason, and to this record. A second
test fails when a class of the list no longer compares without regard to case, so that the list does not grow stale.

Not retained:
- **Case-insensitive options, case-sensitive properties** (the state after 0011): two rules for two kinds of name that a user
  types side by side (`sort size --desc`).
- **Case-insensitive everywhere**, as PowerShell: the opposite of Java, and of the Unix commands that a user brings along
  (`-a` is not `-A`).
- **Case-sensitive file names on Windows and macOS**: the system resolves a name there; a shell that refused `Docs` for `docs`
  would be stricter than the file system it stands on, and would not find what the system itself finds.
- **Upper-case values for enums** (`--view STAGES`): the help, the completion and the messages already wrote lower case, and a
  rule that the user reads in the help is the one to keep. The constants of the code stay upper-case, as Java wants.

## Consequences
- A script that wrote `--Property`, `--view STAGES`, `where dir == TRUE`, `2MB`, `RoundingMode.half_up`, `POWERJ_LANG=FR` or
  `ps JAVA*` (on Linux and macOS) stops working, with an error that names the spelling expected. It is allowed before the first
  release ([#47](https://github.com/yp44/powerj/issues/47)); it is announced in the CHANGELOG.
- One rule to learn, and one to explain: *the case matters, as in Java, except where the system or the user says otherwise*.
- The completion proposes names that the shell accepts as they are (it already proposed lower case).
- A change that adds a comparison without regard to case has to say why, in a test, before it can be merged.
- Windows and macOS rules are tested on their own systems by the CI (the tests that depend on the system are written with
  `Host.windows()` and are skipped elsewhere); they cannot be run on Linux.
