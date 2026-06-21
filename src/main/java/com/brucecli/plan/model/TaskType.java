package com.brucecli.plan.model;

/**
 * 文章中定义的 6 种任务类型。
 *
 * <p>Plan-and-Execute 的关键是“先规划任务，再按类型执行”。
 * type 字段让执行器知道这个 Task 应该映射到哪个工具或哪段本地逻辑。</p>
 */
public enum TaskType {
    /**
     * 规划或决策任务，通常由 Planner 完成，执行阶段只记录说明。
     */
    PLANNING,

    /**
     * 读取文件，映射到上一期模块中的 read_file 工具。
     */
    FILE_READ,

    /**
     * 写入文件，映射到 write_file 工具。
     */
    FILE_WRITE,

    /**
     * 执行命令，映射到 execute_command 工具。
     */
    COMMAND,

    /**
     * 分析中间结果。学习版里默认记录分析说明，也可携带 command 进行验证式分析。
     */
    ANALYSIS,

    /**
     * 验证结果。常见做法是执行 mvn test、curl、java -jar 等命令。
     */
    VERIFICATION
}
