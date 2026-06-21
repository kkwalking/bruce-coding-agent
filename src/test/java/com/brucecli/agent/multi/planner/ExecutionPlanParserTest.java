package com.brucecli.agent.multi.planner;

import com.brucecli.agent.multi.model.ExecutionPlan;
import com.brucecli.agent.multi.model.StepType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionPlanParserTest {
    private final ExecutionPlanParser parser = new ExecutionPlanParser();

    @Test
    void parsePlanAndFindExecutableStepsByDependencyStatus() throws Exception {
        ExecutionPlan plan = parser.parse("""
            ```json
            {
              "goal": "创建并验证项目",
              "steps": [
                {
                  "id": "step_1",
                  "description": "创建项目文件",
                  "type": "FILE_WRITE",
                  "dependencies": []
                },
                {
                  "id": "step_2",
                  "description": "运行测试",
                  "type": "COMMAND",
                  "dependencies": ["step_1"]
                }
              ]
            }
            ```
            """, "fallback");

        assertEquals("创建并验证项目", plan.goal());
        var step1 = plan.steps().get(0);
        var step2 = plan.steps().get(1);
        assertEquals(StepType.FILE_WRITE, step1.type());
        assertEquals(List.of("step_1"), step2.dependencies());
        assertEquals(List.of(step1), plan.getExecutableSteps());

        step1.markStarted();
        step1.markCompleted("created");

        assertEquals(List.of(step2), plan.getExecutableSteps());
    }

    @Test
    void rejectMissingDependency() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
            {
              "goal": "坏计划",
              "steps": [
                {
                  "id": "step_1",
                  "description": "运行测试",
                  "type": "COMMAND",
                  "dependencies": ["missing"]
                }
              ]
            }
            """, "fallback"));
    }
}
