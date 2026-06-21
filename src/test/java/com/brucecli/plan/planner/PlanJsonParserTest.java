package com.brucecli.plan.planner;

import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.TaskType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanJsonParserTest {
    @Test
    void parsesPlanFromMarkdownJsonBlock() throws Exception {
        String json = """
            ```json
            {
              "goal": "查看当前目录",
              "tasks": [
                {
                  "id": "t1",
                  "description": "执行 pwd",
                  "type": "COMMAND",
                  "command": "pwd",
                  "dependencies": []
                },
                {
                  "id": "t2",
                  "description": "验证命令完成",
                  "type": "VERIFICATION",
                  "command": "test -n \\"$(pwd)\\"",
                  "dependencies": ["t1"]
                }
              ]
            }
            ```
            """;

        ExecutionPlan plan = new PlanJsonParser().parse(json, "fallback");

        assertEquals("查看当前目录", plan.goal());
        assertEquals(2, plan.tasks().size());
        // 文章里的 parsePlan 会做一层 ID 归一化，避免 LLM 生成的 t1、step-a 等 ID 影响后续依赖处理。
        assertEquals(TaskType.COMMAND, plan.task("task_1").type());
        assertEquals("task_1", plan.task("task_2").dependencies().get(0));
        assertEquals("task_1", plan.topologicalOrder().get(0).id());
        assertEquals("task_2", plan.topologicalOrder().get(1).id());
    }
}
