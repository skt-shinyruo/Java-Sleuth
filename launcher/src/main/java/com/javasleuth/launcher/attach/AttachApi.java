package com.javasleuth.launcher.attach;

/**
 * Attach API 抽象。
 *
 * <p>生产环境使用工具实现，测试环境可注入 fake 实现以避免真实 attach。</p>
 */
public interface AttachApi {
    VirtualMachineHandle attach(String pid) throws Exception;
}

