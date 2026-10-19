# Acceptance test — Mini-iteration 25: HTTP calls with the JDK client

**Goal:** verify that HTTP calls are made with the JDK's `java.net.http.HttpClient`, without a cmdlet, and that the pipeline gives their parameters: one request per object received, its payload read as objects by `from-json`, a block kept in a variable as a reusable call, and the errors reported as specified (FR-55b).

> Messages are shown in the system language; set `language=en` in config.properties (or `POWERJ_LANG=en`) to get the English texts quoted here.

## Getting the deliverables

As in the previous steps: **Actions** tab, latest run of the **CI** workflow, artifact `powerj-windows-x64-installer` or `powerj-windows-x64-portable`. On Linux or macOS, build with `./mvnw verify` and run `powerj-dist/target/…/bin/powerj`.

## Preparation

An Internet access to the public test API `https://jsonplaceholder.typicode.com` (fake data, writes are simulated). Behind a proxy, see the limitations below. Then, in PowerJ:

```text
$c = HttpClient.newHttpClient()
$api = "https://jsonplaceholder.typicode.com"
$get = { path -> $c.send(HttpRequest.newBuilder(URI.create($api + path)).build(), HttpResponse.BodyHandlers.ofString()).body() }
```

## Scenario

| # | Action | Expected result |
|---|---|---|
| 1 | `$c.send(HttpRequest.newBuilder(URI.create("$api/users/1")).build(), HttpResponse.BodyHandlers.ofString()).body()` | The JSON text of user 1, `Leanne Graham`. |
| 2 | `List.of(1, 2, 3) \| map { id -> $c.send(HttpRequest.newBuilder(URI.create("$api/users/$(id)")).build(), HttpResponse.BodyHandlers.ofString()).body() } \| from-json \| select id name email` | Table `id name email` of users 1, 2 and 3. |
| 3 | `$get.invoke("/users") \| from-json \| count` | `10`. |
| 4 | `($get.invoke("/users/1") \| from-json).address.city` | The city of user 1. |
| 5 | `List.of("/posts/1", "/posts/2") \| map $get \| from-json \| select id title` | Two rows, `id` 1 and 2, with their titles. |
| 6 | `List.of("first", "second") \| map { t -> Map.of("title", t, "userId", 1) } \| to-json -l \| map { j -> $c.send(HttpRequest.newBuilder(URI.create("$api/posts")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(j)).build(), HttpResponse.BodyHandlers.ofString()) } \| select status={ $_.statusCode() } body={ $_.body() }` | Two rows: status `201`, and a body that repeats the title sent, with `"id": 101`. |
| 7 | `Files.writeString(Path.of("calls.csv"), "method,path\nGET,/users/1\nDELETE,/posts/1\n")`, then `cat calls.csv \| from-csv \| map { r -> $c.send(HttpRequest.newBuilder(URI.create($api + r.path)).method(r.method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()) } \| select status={ $_.statusCode() } url={ $_.uri() }` | `calls.csv`, then two rows with status `200`: the method and the path of each call come from the CSV. |
| 8 | `$r = $c.send(HttpRequest.newBuilder(URI.create("$api/users/999")).build(), HttpResponse.BodyHandlers.ofString())`, then `$r.statusCode()`, then `$r` | No error: `404`, then `(GET https://jsonplaceholder.typicode.com/users/999) 404`. |
| 9 | `$r.statusCode` | `HttpResponseImpl has no property 'statusCode' (method: statusCode())`: the response's accessors are methods. |
| 10 | `List.of(1, 999) \| map { id -> $c.send(HttpRequest.newBuilder(URI.create("$api/users/$(id)")).build(), HttpResponse.BodyHandlers.ofString()) } \| where { $_.statusCode() == 200 } \| map { $_.uri().path }` | `/users/1` only. |
| 11 | `$c.send(HttpRequest.newBuilder(URI.create("https://www.example.com")).build(), HttpResponse.BodyHandlers.ofLines()).body() \| grep -i title` | The line `<title>Example Domain</title>`: the lines of the body are unrolled. |
| 12 | `$c.send(HttpRequest.newBuilder(URI.create("$api/users/1")).build(), HttpResponse.BodyHandlers.ofFile(Path.of("user1.json"))).body()`, then `cat user1.json \| from-json` | `user1.json`, then user 1: the payload is written to the file. |
| 13 | `List.of("/users/1", "/bad path") \| map $get` | The JSON of user 1, and the error `map: java.lang.IllegalArgumentException: Illegal character in path at index 40: https://jsonplaceholder.typicode.com/bad path (object skipped: /bad path)`. |
| 14 | `HttpClient.newBuilder().connectTimeout(2s).build().send(HttpRequest.newBuilder(URI.create("http://10.255.255.1")).build(), HttpResponse.BodyHandlers.ofString())` | After about 2 s, `java.net.http.HttpConnectTimeoutException: HTTP connect timed out`; PowerJ goes on. |
| 15 | The same call without `.connectTimeout(2s)`, then Ctrl+C | The prompt comes back. |
| 16 | `$f = List.of(1, 2, 3) \| map { id -> $c.sendAsync(HttpRequest.newBuilder(URI.create("$api/users/$(id)")).build(), HttpResponse.BodyHandlers.ofString()) }`, then `$f \| map { f -> f.join().body() } \| from-json \| map { $_.name }` | The three names, in the order of the ids. |
| 17 | Put the three lines of the preparation in `profile.pj` (`$profile` gives its path), restart PowerJ, then `($get.invoke("/users/2") \| from-json).name` | `Ervin Howell`: the client and the block are ready in every session. |

## Known limitations

- There is no `http` cmdlet: the requests are written in Java, with `HttpRequest.newBuilder(…)`.
- `HttpResponse` is an interface: `statusCode()`, `body()`, `headers()` and `uri()` need their parentheses.
- For some network failures (connection refused, unknown host), the JDK gives no message: the error is only `java.net.ConnectException`.
- The `HTTPS_PROXY` variable is not read: behind a proxy, build the client with `HttpClient.newBuilder().proxy(ProxySelector.of(new InetSocketAddress("proxy", 8080))).build()`.
- A binary payload (archive, image) is written to a file with `HttpResponse.BodyHandlers.ofFile(…)`, not read as text.
