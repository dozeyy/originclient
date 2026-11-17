package com.vulkairis.test;

import com.vulkairis.core.PackReadinessState;
import com.vulkairis.core.PackReadinessStateMachine;
import com.vulkairis.iris.SourceProvenance;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PackExecutionStateTest {
    @Test
    public void testResetToNoPack() {
        PackReadinessStateMachine sm = new PackReadinessStateMachine();
        sm.reset(SourceProvenance.currentGeneration(), "test");
        Assertions.assertEquals(PackReadinessState.NO_PACK, sm.getState());
    }
}