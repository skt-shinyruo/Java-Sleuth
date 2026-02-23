package com.javasleuth.core.command;

import com.javasleuth.foundation.security.CommandMeta;
import java.util.Collections;
import java.util.Map;

/**
 * 命令提供者（用于内建命令与插件命令扩展）。
 *
 * <p>兼容性约束（对插件作者）：</p>
 * <ul>
 *   <li>该接口属于 Java-Sleuth 的“插件 API 面”，应尽量保持向后兼容。</li>
 *   <li>新增能力优先通过 {@code default} 方法扩展，避免破坏既有实现。</li>
 *   <li>插件建议以固定版本的 Java-Sleuth 进行编译与发布，并在升级时做兼容性验证。</li>
 * </ul>
 */
public interface CommandProvider {
    String getName();

    Map<String, Command> getCommands();

    default Map<String, CommandMeta> getCommandMeta() {
        return Collections.emptyMap();
    }
}
