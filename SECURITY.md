# Security policy

## Supported versions

PowerJ has not been released yet: the development branch (`main`, version `0.2.0-SNAPSHOT`) is the only supported
version. From the first release on, this table will say which versions get fixes.

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's private reporting: on the repository page, **Security** →
**Report a vulnerability**. Say what you did, what you expected and what happened, and the version (`powerj --version`).

You will get an answer saying whether the report is understood and in scope. A confirmed problem is fixed on `main`, with
a test that fails without the fix, and named in the [CHANGELOG](CHANGELOG.md) once the fix is published.

## What PowerJ is, security-wise

PowerJ is a shell. It runs **with the rights of the user who started it** and does what that user types: it starts
programs, reads and deletes files, and, because Java is its expression language, calls any public method of the JRE.
Everything below follows from that.

### Guard-rails against accidents (not security boundaries)

Some features stop a *mistake*. They are not meant to stop someone who wants to get around them, and a report that
only shows that they can be bypassed on purpose is out of scope.

| Feature | What it stops | What it does not stop |
|---|---|---|
| `System.exit`, `Runtime.exit`/`halt` typed in a line | ending the shell by accident: the call becomes an exit request, honoured when the line ends | calls through a `MethodHandle` or native code (`CallPolicy` sits on `JavaInvoker`) |
| `System.setOut`/`setErr`/`setIn` | breaking the display | the same |
| Protected paths (`rm`, `mv`; the `protected-paths` file of the configuration folder, with platform defaults) | removing a whole system or home folder by typing the wrong path; aliases and links to a protected folder are resolved | removing the *content* of a protected folder (`rm /etc/hosts` is allowed); a path replaced by a link between the check and the removal; Windows 8.3 names, `\\?\` prefixes and junctions are not specifically handled |
| Preview mode (Alt+V) | running a line with side effects while it is only being typed: only what `PreviewPolicy` allows runs | — |
| Tab completion | it runs no command and calls no getter on the values it looks at | — |

### Third-party modules: full trust

A jar placed in `~/.powerj/modules` is loaded at start-up and **runs with the rights of the shell**, like any program the
user starts. PowerJ gives it its own `ModuleLayer`, which isolates its *dependencies* from the shell's, not its behavior.
It also opens the module's packages to PowerJ, which reads the annotations of its cmdlets by reflection.

A module that uses native code (JNI, FFM) is not loaded until the user agrees: the question is asked with `[y/N]` (the
default is no), never when there is no terminal, and the answer is kept in `config.properties` as
`modules.nativeAccess.<module>`. A yes is tied to the **content**: it holds the SHA-256 of every module that would be loaded
together (the jar of the module and of its dependencies), so a jar that is replaced, or a dependency that changes, asks
again. A no holds whatever the content ([ADR 0006](docs/adr/0006-consent-to-native-access-is-tied-to-the-content.md)).

PowerJ does not verify the origin of a module (there is no signature), only that the content it loads is the content the
user agreed to when native access is at stake. Only put in `~/.powerj/modules` jars you would run.

### Native programs and files

Native programs are started with an argument list, never through another shell, and with an explicit environment.
File operations do not follow symbolic links (`NOFOLLOW_LINKS`): removing a link removes the link.

## In scope

- A bypass of the native-access consent, or a module loaded when the consent was refused or could not be asked.
- A command that deletes, overwrites or copies where its documentation and its guard-rails say it will not.
- A way for another local user to make the shell run something by changing the files of the configuration folder
  (`config.properties`, `protected-paths`, `profile.pj`, the modules folder) that the permissions of the folder should
  have kept out of reach.

## Out of scope

- That a typed Java expression can do whatever the user's account can: that is the feature.
- That a module you installed does harm.
- That a guard-rail of the table above can be gotten around on purpose.
- A denial of service by a command that the user runs on purpose (an endless loop, a huge file).

The reasoning behind these limits is in the [architecture review](docs/REVUE-ARCHITECTURE.md) (C-12, C-21, C-23, C-24)
and in [docs/adr/](docs/adr/).
