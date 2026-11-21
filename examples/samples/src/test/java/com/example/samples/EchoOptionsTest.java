package com.example.samples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;
import io.powerj.testing.CmdletDriver;
import io.powerj.testing.TestContext;

class EchoOptionsTest {

    private final TestContext<OptionValues> context = new TestContext<>(Path.of("."));

    /** A block that records the {@code $_} of each call and answers with {@code body}. */
    private static final class RecordingBlock implements ScriptBlock {
        final List<Object> calls = new ArrayList<>();
        private final Function<Object, Object> body;

        RecordingBlock(Function<Object, Object> body) {
            this.body = body;
        }

        @Override
        public Object invoke(Object current) {
            calls.add(current);
            return body.apply(current);
        }

        @Override
        public String source() {
            return "test";
        }
    }

    /** What the shell gives for {@code echo-options} without arguments: the default of each type. */
    private static EchoOptionsParams defaults(Object block) {
        return new EchoOptionsParams(List.of(), null, 0, null, 0L, 0.0, 0f, (short) 0, (byte) 0, '\0', false, null,
                null, null, List.of(), block, null);
    }

    private OptionValues run(EchoOptionsParams params) throws Exception {
        List<OptionValues> emitted = CmdletDriver.run(new EchoOptions(), params, context);
        assertThat(emitted).hasSize(1);
        return emitted.getFirst();
    }

    @Test
    void emitsOneObjectThatCopiesEveryOption() throws Exception {
        Pattern pattern = Pattern.compile("a.*");
        var params = new EchoOptionsParams(List.of("one", "two"), "hello", 3, 7, 10_240L, 0.5, 1.5f, (short) 300,
                (byte) 12, 'x', true, Duration.ofMillis(1500), Path.of("docs"), Level.HIGH, List.of("a", "b"), 42,
                pattern);
        OptionValues values = run(params);
        assertThat(values).isEqualTo(new OptionValues(List.of("one", "two"), "hello", 3, 7, 10_240L, 0.5, 1.5f,
                (short) 300, (byte) 12, 'x', true, Duration.ofMillis(1500), Path.of("docs"), Level.HIGH,
                List.of("a", "b"), 42, pattern, 42));
        assertThat(context.errors).isEmpty();
    }

    @Test
    void everyOptionHasItsComponentInTheOutputInTheSameOrder() {
        List<String> options = Arrays.stream(EchoOptionsParams.class.getRecordComponents()).map(c -> c.getName()).toList();
        List<String> outputs = Arrays.stream(OptionValues.class.getRecordComponents()).map(c -> c.getName()).toList();
        assertThat(outputs).containsExactlyElementsOf(concat(options, "blockValue"));
    }

    @Test
    void withoutOptionsEachValueIsTheDefaultOfItsType() throws Exception {
        OptionValues values = run(defaults(null));
        assertThat(values.words()).isEmpty();
        assertThat(values.tags()).isEmpty();
        assertThat(values.number()).isNull(); // boxed: absent is null, while the primitive count is 0
        assertThat(values.count()).isZero();
        assertThat(values.letter()).isEqualTo('\0');
        assertThat(values.block()).isNull();
        assertThat(values.blockValue()).isNull();
    }

    @Test
    void aBlockIsEvaluatedOnceByTheCmdletWithNoCurrentObject() throws Exception {
        var block = new RecordingBlock(current -> 3);
        OptionValues values = run(defaults(block));
        assertThat(block.calls).containsExactly((Object) null);
        assertThat(values.block()).isSameAs(block); // the block itself is kept, its result is beside it
        assertThat(values.blockValue()).isEqualTo(3);
    }

    @Test
    void aValueThatIsNotABlockIsCopiedAsItIs() throws Exception {
        OptionValues values = run(defaults(3)); // --block (1 + 2): the shell gave the value
        assertThat(values.block()).isEqualTo(3);
        assertThat(values.blockValue()).isEqualTo(3);
    }

    @Test
    void aBlockThatFailsIsANonBlockingErrorAndTheObjectIsStillEmitted() throws Exception {
        var block = new RecordingBlock(current -> {
            throw new ArithmeticException("/ by zero");
        });
        OptionValues values = run(defaults(block));
        assertThat(values.blockValue()).isNull();
        assertThat(values.block()).isSameAs(block);
        assertThat(context.errors).containsExactly("--block: / by zero");
    }

    @Test
    void aCancellationFromTheBlockIsThrownNotReported() {
        var block = new RecordingBlock(current -> {
            throw new CancellationException("Ctrl+C");
        });
        assertThatThrownBy(() -> CmdletDriver.run(new EchoOptions(), defaults(block), context))
                .isInstanceOf(CancellationException.class);
        assertThat(context.errors).isEmpty();
        assertThat(context.emitted).isEmpty();
    }

    @Test
    void onlyTCAndVHaveAShortFormAndNoOptionIsMandatory() {
        List<Character> letters = Arrays.stream(EchoOptionsParams.class.getRecordComponents())
                .map(c -> c.getAnnotation(Option.class).shortName()).filter(letter -> letter != '\0').toList();
        assertThat(letters).containsExactly('t', 'c', 'v');
        assertThat(EchoOptionsParams.class.getRecordComponents())
                .noneMatch(c -> c.getAnnotation(Option.class).mandatory());
        assertThat(EchoOptionsParams.class.getRecordComponents()[0].getAnnotation(Option.class).position()).isZero();
    }

    @Test
    void theCmdletIsPreviewableAndListedAfterPassthrough() {
        CmdletInfo info = EchoOptions.class.getAnnotation(CmdletInfo.class);
        assertThat(info.name()).isEqualTo("echo-options");
        assertThat(info.preview()).isTrue();
        assertThat(info.passThrough()).isFalse();
        assertThat(new SamplesProvider().cmdlets()).map(c -> c.getClass().getSimpleName())
                .containsExactly("Passthrough", "EchoOptions");
    }

    private static List<String> concat(List<String> list, String last) {
        List<String> all = new ArrayList<>(list);
        all.add(last);
        return all;
    }
}
