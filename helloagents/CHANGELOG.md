# Changelog

This file records all important project changes.
Format based on [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/),
version numbers follow [Semantic Versioning](https://semver.org/lang/zh-CN/).

## [Unreleased]

### Added
- 插件化命令注册与分帧协议基础设施
- auth 命令与会话角色绑定
- HELLO/CONFIG 握手协商与 binary 严格二进制帧协议
- security.mode=off|hmac（默认 off）与 HMAC+nonce 请求签名/基础防重放
- server.bind.address 默认 127.0.0.1（降低默认口令/明文传输暴露面）
- 插件命令动态权限注册（避免 unknown command 被 AuthorizationManager 拒绝）
- 协议/插件/安全相关指标：handshake、binary upgrade、plugin load、security_verify
- watch/trace 事件丢弃/采样计数与 status 输出

### Changed
- CommandProcessor 改为注册表 + 统一执行管线
- Launcher 支持 framed/stream 协议与端口配置读取
- Enhancer 支持链式叠加与按会话移除
- CommandProcessor 支持 bind address + handshake 协商并可升级 binary 通道
- Launcher 支持 handshake 协商与 binary 通道；在 security.mode=hmac 时自动封装 SIG 请求

### Fixed
- watch/trace 队列增加背压与采样
- CommandParser 反斜杠转义字符解析修复
- PerformanceOptimizer/MemoryOptimizer 编译问题修复（静态 API/缓存清理/ MBean 接口）

## [1.0.0] - 2026-01-28

### Added
- 初始化知识库文档结构与项目概览
