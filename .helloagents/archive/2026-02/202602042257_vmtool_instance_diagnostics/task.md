# task

Tasks:

- [√] 增加 vmtool 追踪拦截器（弱引用 + 有界缓存）
- [√] 增加 InstanceTrackEnhancer：构造器 RETURN 上报实例（避免 this(...) 重复）
- [√] 增加 VmToolSessionRegistry：track/stop/stopAll 的 enhancer 生命周期管理
- [√] 增加 vmtool 命令：track/tracks/instances/inspect/invoke/invoke-static/histogram
- [√] 增加对象检视与条件过滤：SleuthObjectInspector + VmToolObjectConditionEvaluator
- [√] 接入 reset：reset 时清理 vmtool 会话
- [√] 更新文档：docs/usage/commands.md
- [√] 更新知识库：helloagents/wiki/modules/*
- [√] 增加单测：vmtool invoker/interceptor/inspector
- [√] 运行 mvn test 验证通过

