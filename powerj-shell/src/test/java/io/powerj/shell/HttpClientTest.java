package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;

/**
 * HTTP calls with the JDK's {@code HttpClient}, driven by the pipeline (FR-55): no {@code http} cmdlet, the
 * {@code java.net.http} classes are called directly, against a small local server.
 */
class HttpClientTest {

    private static final Map<String, String> USERS = Map.of(
            "1", "{\"id\": 1, \"name\": \"Alice\", \"city\": \"Nantes\"}",
            "2", "{\"id\": 2, \"name\": \"Bob\", \"city\": \"Lyon\"}",
            "3", "{\"id\": 3, \"name\": \"Chloé\", \"city\": \"Paris\"}");

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private ServerSocket server;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::serve);
        Map<String, String> env = new HashMap<>(System.getenv());
        var session = new Session(tmp, tmp, env);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false),
                Map.of(), CmdletRegistry.discover());
        run("$c = HttpClient.newHttpClient()");
        run("$base = \"http://127.0.0.1:" + server.getLocalPort() + "\"");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private String run(String line) throws Exception {
        String output = execute(line);
        assertThat(errors).isEmpty();
        return output;
    }

    private String execute(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void getsOnePayloadPerPipelineObject() throws Exception {
        assertThat(run("List.of(1, 2, 3) | map { id -> $c.send(HttpRequest.newBuilder(URI.create($base + \"/users/\" + id))"
                + ".build(), HttpResponse.BodyHandlers.ofString()).body() } | from-json | map { $_.name }"))
                .isEqualTo("Alice\nBob\nChloé\n");
    }

    @Test
    void blockInAVariableIsReusedByMap() throws Exception {
        run("$get = { url -> $c.send(HttpRequest.newBuilder(URI.create(url)).build(), "
                + "HttpResponse.BodyHandlers.ofString()).body() }");
        assertThat(run("List.of(\"$base/users/2\", \"$base/users/3\") | map $get | from-json | map { $_.city }"))
                .isEqualTo("Lyon\nParis\n");
        assertThat(run("($get.invoke(\"$base/users/1\") | from-json).name")).isEqualTo("Alice\n");
    }

    @Test
    void aFailedCallSkipsItsObject() throws Exception {
        run("$get = { url -> $c.send(HttpRequest.newBuilder(URI.create(url)).build(), "
                + "HttpResponse.BodyHandlers.ofString()).body() }");
        assertThat(execute("List.of(\"not a url\", \"$base/users/1\") | map $get | from-json | map { $_.name }"))
                .isEqualTo("Alice\n");
        assertThat(errors).singleElement().asString()
                .startsWith("map: java.lang.IllegalArgumentException").endsWith("(object skipped: not a url)");
    }

    @Test
    void postsWithHeadersAndBodyTakenFromThePipeline() throws Exception {
        Files.writeString(tmp.resolve("calls.csv"), "path,token,body\n/a,t1,{}\n/b,t2,[]\n");
        assertThat(run("cat calls.csv | from-csv | map { r -> $c.send(HttpRequest.newBuilder(URI.create($base + r.path))"
                + ".header(\"Content-Type\", \"application/json\").header(\"Authorization\", \"Bearer \" + r.token)"
                + ".POST(HttpRequest.BodyPublishers.ofString(r.body)).build(), HttpResponse.BodyHandlers.ofString())"
                + ".body() } | from-json | map { \"$($_.method) $($_.path) $($_.authorization) $($_.body)\" }"))
                .isEqualTo("POST /a Bearer t1 {}\nPOST /b Bearer t2 []\n");
    }

    @Test
    void sendsTheJsonOfEachObject() throws Exception {
        assertThat(run("List.of(1, 3) | map { id -> Map.of(\"id\", id) } | to-json -l | map { j -> $c.send("
                + "HttpRequest.newBuilder(URI.create(\"$base/items\")).POST(HttpRequest.BodyPublishers.ofString(j))"
                + ".build(), HttpResponse.BodyHandlers.ofString()).body() } | from-json | map { $_.body }"))
                .isEqualTo("{\"id\":1}\n{\"id\":3}\n");
    }

    @Test
    void keepsTheWholeResponse() throws Exception {
        run("$r = $c.send(HttpRequest.newBuilder(URI.create(\"$base/users/9\")).timeout(5s).build(), "
                + "HttpResponse.BodyHandlers.ofString())");
        assertThat(run("$r.statusCode()")).isEqualTo("404\n");
        assertThat(run("$r.headers().firstValue(\"content-type\").orElse(\"?\")")).isEqualTo("application/json\n");
        assertThat(run("List.of(1, 9) | map { id -> $c.send(HttpRequest.newBuilder(URI.create(\"$base/users/$(id)\"))"
                + ".build(), HttpResponse.BodyHandlers.ofString()) } | where { $_.statusCode() == 200 } "
                + "| map { $_.uri().path }")).isEqualTo("/users/1\n");
    }

    @Test
    void linesOfTheBodyAreUnrolled() throws Exception {
        assertThat(run("$c.send(HttpRequest.newBuilder(URI.create(\"$base/text\")).build(), "
                + "HttpResponse.BodyHandlers.ofLines()).body() | where { $_.endsWith(\"2\") }")).isEqualTo("line 2\n");
    }

    @Test
    void sendsAsynchronously() throws Exception {
        // the assignment starts every request before the first one is awaited
        run("$futures = List.of(1, 2, 3) | map { id -> $c.sendAsync(HttpRequest.newBuilder("
                + "URI.create(\"$base/users/$(id)\")).build(), HttpResponse.BodyHandlers.ofString()) }");
        assertThat(run("$futures | map { f -> f.join().body() } | from-json | map { $_.id }"))
                .isEqualTo("1\n2\n3\n");
    }

    /** Answers each connection: {@code GET /users/<id>}, {@code GET /text}, and any POST is echoed as JSON. */
    private void serve() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> answer(socket));
            } catch (IOException e) {
                return; // closed by tearDown
            }
        }
    }

    private static void answer(Socket socket) {
        try (socket) {
            // ISO-8859-1: one char per byte, so that Content-Length counts the chars of the body
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String[] request = in.readLine().split(" ");
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                headers.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
            }
            char[] chars = new char[Integer.parseInt(headers.getOrDefault("content-length", "0"))];
            for (int read = 0; read < chars.length; ) {
                read += in.read(chars, read, chars.length - read);
            }
            String body = new String(new String(chars).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            String method = request[0];
            String path = request[1];
            if (method.equals("POST")) {
                respond(socket, 201, "application/json", "{\"method\": \"POST\", \"path\": " + quote(path)
                        + ", \"authorization\": " + quote(headers.get("authorization")) + ", \"body\": " + quote(body)
                        + "}");
            } else if (path.equals("/text")) {
                respond(socket, 200, "text/plain; charset=utf-8", "line 1\nline 2\nline 3\n");
            } else if (path.startsWith("/users/") && USERS.containsKey(path.substring(7))) {
                respond(socket, 200, "application/json", USERS.get(path.substring(7)));
            } else {
                respond(socket, 404, "application/json", "{\"error\": \"not found\"}");
            }
        } catch (IOException | RuntimeException e) {
            // the client sees a closed connection
        }
    }

    private static void respond(Socket socket, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String quote(String text) {
        return text == null ? "null" : "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
