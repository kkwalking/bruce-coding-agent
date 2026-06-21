# 项目规则

- 本项目是 `~/code/kcode` 各学习章节能力的单模块 Maven 集成实现。
- 保持 `src/main/java` 下各能力包边界清晰，不拆分为 Maven 多模块。
- 默认可执行入口为 `com.brucecli.integrated.cli.IntegratedMain`。
- 新功能优先接入统一运行时和统一 CLI，同时保留底层能力可独立测试。
- 项目品牌、示例名称和终端文案统一使用 bruce。
