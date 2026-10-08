# Acceptance test — Step 5: Java interoperability

**Goal:** verify that the JRE's Java API can be called directly from the shell (static methods, fields, `new`, instance methods, blocks passed as lambdas), that Java errors are readable, and that Ctrl+C stays in control of Java calls.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`.

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `java.util.List.of("apple", "banana", "orange") \| where { $_.contains("b") }` | `banana`. |
| 2 | `$l = java.util.List.of("apple", "banana")` then `$l.size()` | `2`: the list is kept as is in the variable. |
| 3 | `Math.max(3, 7)` then `java.lang.Math.PI` then `DayOfWeek.MONDAY` | `7`, `3.141592653589793`, `MONDAY`. |
| 4 | `LocalDate.now().plusDays(10).dayOfWeek` | The day of the week 10 days from now (`java.time` is imported by default). |
| 5 | `MessageDigest.getInstance("SHA-256")`, then `import java.security.*` and again `MessageDigest.getInstance("SHA-256").algorithm`, then `import` | First `« MessageDigest » inconnu…`, then `SHA-256`; `import` alone lists the active imports. |
| 6 | `new java.io.File("C:\\Windows").listFiles() \| where { f -> f.directory && f.name.startsWith("S") }` | The folders of `C:\Windows` starting with `S` (`System32`…). |
| 7 | `$l.stream().map(s -> s.toUpperCase()).toList()` | `APPLE` then `BANANA`: the lambda becomes a `Function`. |
| 8 | `$m = new java.util.ArrayList($l); $m.sort((a, b) -> b.length() - a.length()); $m` | `banana` then `apple`: a two-parameter lambda becomes a `Comparator`. |
| 9 | `String.format("%s-%05d", "id", 42)` | `id-00042` (varargs and conversions). |
| 10 | `Integer.parseInt("x")` | Red error `java.lang.NumberFormatException : For input string: "x"`. Then `$errors[0].class.name`: `java.lang.NumberFormatException`. |
| 11 | `$debug = true` then `Integer.parseInt("x")`, then `$debug = false` | The same message, followed by the full Java stack trace. |
| 12 | `java -version` | Still launches the native `java` if it is installed (otherwise `commande inconnue : java`). |
| 13 | `help members $l` then `help java.util.List` then `help Math` | Methods and properties of the list; API of `List` (static methods, methods); fields of `Math` (`static PI : double`). |
| 14 | `ls \| where { List.of("txt", "md").contains($_.ext) }` | The `.txt` and `.md` files in the folder. |
| 15 | `Files.size(Path.of("C:\\Windows\\notepad.exe")) / 1kb` | Size of `notepad.exe` in KB. |
| 16 | `"Il est $(LocalTime.now().hour) h, max = $(Math.max(4, 9))"` | The `$( … )` values are inserted into the string. |
| 17 | `[long] 5`, then `[int] 3.9`, then `[java.util.ArrayList] $l` | `5`, `3`, then the error `conversion impossible : … n'est pas un ArrayList`. |
| 18 | `Stream.iterate(0, n -> n + 1).forEach(n -> n)` then Ctrl+C | Immediate stop (`^C`), the shell remains usable. |
| 19 | `BigInteger.valueOf(3).pow(300000000).bitLength()` then Ctrl+C, then Ctrl+C again | The JDK computation ignores the first request; the second one returns control with `commande abandonnée, elle continue en arrière-plan : …`. |
| 20 | `System.out.println("bonjour " + Math.max(1, 2))` | `bonjour 2`, without corrupting the input line. |
| 21 | `System.setOut(null)` | Refused: `System.setOut est refusé : il casserait l'affichage du shell`. |
| 22 | `System.exit(6)` then, in `cmd.exe`, `echo %ERRORLEVEL%` | PowerJ exits cleanly (history saved); `6`. |

## Syntax points to know

- **Lambdas**: this exe includes step 5b. Functions passed to Java are written as in Java (`s -> s.length()`, `(a, b) -> …`, `String::length`); `$_` remains the shorthand for short filters. See acceptance test 5b to know when to use one form or the other.
- At the start of a line, a qualified name **attached** to `(` is a Java call (`Math.max(1, 2)`); without parentheses, it is one if it designates a class or a static field (`Math.PI`). Otherwise it is a command: `java -version`, `notepad.exe fichier.txt`. As an argument to a command, only the call form is an expression: `cat Path.of("a.txt")`.
- Outside parentheses, an expression at the start of a line stops where command syntax resumes: `>` redirects, `&&` and `||` chain, `|` passes to the pipeline. To compare or combine, use parentheses or a block: `($a > 1 && $b)`.
- Accesses are written without spaces: `$l.size()`, not `$l .size()` (a space separates a command's arguments).
- The default packages (`java.lang`, `java.util`, `java.io`, `java.nio.file`, `java.time`, `java.net`…) do not need an `import`; other JDK classes are used by their fully qualified name or after an `import`.
- Only the Java standard library is accessible. For a third-party library, you write a cmdlet (step 7).

## Known limitations of step 5

- No `Classe.class` and no method references (`String::length`): use `$x.getClass()` and `{ … }` blocks.
- An abandoned command (scenario 19) keeps consuming CPU until it finishes or the shell is closed.
- No Tab completion yet, including for Java classes and methods (step 6).
