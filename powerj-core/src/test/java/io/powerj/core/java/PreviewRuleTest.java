package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.powerj.core.java.PreviewRule.Verdict;

/** The rules of the preview policy, one by one, and the way they compose: the first opinion decides. */
class PreviewRuleTest {

    private static Executable method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        return type.getMethod(name, parameters);
    }

    @Test
    void whenGivesItsVerdictToWhatMatchesAndHasNoOpinionOtherwise() throws Exception {
        var rule = PreviewRule.when(e -> e.getName().startsWith("to"), Verdict.ALLOW);

        assertThat(rule.judge(method(Object.class, "toString"))).isEqualTo(Verdict.ALLOW);
        assertThat(rule.judge(method(Object.class, "hashCode"))).isEqualTo(Verdict.ABSTAIN);
    }

    @Test
    void decidesForSpeaksOnlyOfTheMethodsOfItsType() throws Exception {
        var rule = PreviewRule.decidesFor(String.class, name -> name.equals("length"));

        assertThat(rule.judge(method(String.class, "length"))).isEqualTo(Verdict.ALLOW);
        assertThat(rule.judge(method(String.class, "isEmpty"))).isEqualTo(Verdict.REFUSE);
        assertThat(rule.judge(method(Object.class, "hashCode"))).isEqualTo(Verdict.ABSTAIN);
        // getMethod gives the declaring class: String.hashCode is declared by String itself
        assertThat(rule.judge(method(String.class, "hashCode"))).isEqualTo(Verdict.REFUSE);
    }

    @Test
    void constructorsOfAllowsTheListedClassesAndRefusesTheOthers() throws Exception {
        var rule = PreviewRule.constructorsOf(Set.of(ArrayList.class));

        assertThat(rule.judge(ArrayList.class.getConstructor())).isEqualTo(Verdict.ALLOW);
        assertThat(rule.judge(java.util.LinkedList.class.getConstructor())).isEqualTo(Verdict.REFUSE);
        assertThat(rule.judge(method(ArrayList.class, "size"))).isEqualTo(Verdict.ABSTAIN);
    }

    @Test
    void theFirstRuleWithAnOpinionDecides() throws Exception {
        var refuseStrings = PreviewRule.when(e -> e.getDeclaringClass() == String.class, Verdict.REFUSE);
        var allowAll = PreviewRule.when(_ -> true, Verdict.ALLOW);

        assertThat(new PreviewPolicy(List.of(refuseStrings, allowAll)).permits(method(String.class, "length"))).isFalse();
        assertThat(new PreviewPolicy(List.of(allowAll, refuseStrings)).permits(method(String.class, "length"))).isTrue();
    }

    @Test
    void aRuleThatAbstainsLeavesTheDecisionToTheNext() throws Exception {
        var abstain = PreviewRule.when(_ -> false, Verdict.REFUSE);
        var allow = PreviewRule.when(_ -> true, Verdict.ALLOW);

        assertThat(new PreviewPolicy(List.of(abstain, abstain, allow)).permits(method(Object.class, "toString"))).isTrue();
    }

    @Test
    void whenNoRuleAllowsItTheMethodIsRefused() throws Exception {
        assertThat(new PreviewPolicy(List.of()).permits(method(Object.class, "toString"))).isFalse();
        assertThat(new PreviewPolicy(List.of(PreviewRule.when(_ -> false, Verdict.ALLOW)))
                .permits(method(Object.class, "toString"))).isFalse();
    }

    @Test
    void thePolicyKeepsItsOwnCopyOfTheRules() throws Exception {
        var rules = new ArrayList<PreviewRule>();
        var policy = new PreviewPolicy(rules);
        rules.add(PreviewRule.when(_ -> true, Verdict.ALLOW));

        assertThat(policy.permits(method(Object.class, "toString"))).isFalse();
    }
}
