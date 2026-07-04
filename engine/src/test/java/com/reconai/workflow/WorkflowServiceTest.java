package com.reconai.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class WorkflowServiceTest {

    // ── Valid transitions ─────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "OPEN,INVESTIGATING",
        "OPEN,RESOLUTION_PROPOSED",
        "INVESTIGATING,RESOLUTION_PROPOSED",
        "INVESTIGATING,ESCALATED",
        "RESOLUTION_PROPOSED,RESOLVED",
        "RESOLUTION_PROPOSED,OPEN",
        "ESCALATED,INVESTIGATING",
        "ESCALATED,RESOLVED"
    })
    void isValidTransition_AllLegalMoves_ReturnsTrue(String from, String to) {
        assertThat(WorkflowService.isValidTransition(from, to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "OPEN,RESOLVED",
        "OPEN,ESCALATED",
        "INVESTIGATING,OPEN",
        "RESOLUTION_PROPOSED,INVESTIGATING",
        "RESOLUTION_PROPOSED,ESCALATED",
        "ESCALATED,OPEN",
        "ESCALATED,RESOLUTION_PROPOSED",
        "RESOLVED,OPEN",
        "RESOLVED,INVESTIGATING",
        "RESOLVED,RESOLUTION_PROPOSED",
        "RESOLVED,ESCALATED"
    })
    void isValidTransition_IllegalMoves_ReturnsFalse(String from, String to) {
        assertThat(WorkflowService.isValidTransition(from, to)).isFalse();
    }

    @Test
    void validTransitions_AllSourceStates_ArePresent() {
        Set<String> expectedStates = Set.of("OPEN", "INVESTIGATING", "RESOLUTION_PROPOSED", "ESCALATED", "RESOLVED");
        assertThat(WorkflowService.VALID_TRANSITIONS.keySet()).isEqualTo(expectedStates);
    }

    @Test
    void validTransitions_ResolvedIsTerminal() {
        assertThat(WorkflowService.VALID_TRANSITIONS.get("RESOLVED")).isEmpty();
    }

    @Test
    void isValidTransition_UnknownStatus_ReturnsFalse() {
        assertThat(WorkflowService.isValidTransition("NONEXISTENT", "OPEN")).isFalse();
    }

    @Test
    void isValidTransition_SelfTransition_ReturnsFalse() {
        // Can't stay in the same state
        for (String status : WorkflowService.VALID_TRANSITIONS.keySet()) {
            assertThat(WorkflowService.isValidTransition(status, status))
                .as("self-transition for " + status)
                .isFalse();
        }
    }
}
