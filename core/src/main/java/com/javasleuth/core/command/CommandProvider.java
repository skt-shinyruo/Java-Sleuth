package com.javasleuth.core.command;

import com.javasleuth.foundation.security.CommandMeta;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * 命令提供者（core 内部 provider 与历史插件兼容接口）。
 *
 * <p>外部插件应优先实现 {@link com.javasleuth.core.command.spi.RestrictedCommandProvider}。
 * 该历史接口会继续用于内置命令和 unsafe legacy bridge，但外部插件不会再通过它获得
 * Instrumentation、transformer、auth manager 等 core 内部对象。</p>
 *
 * <p>兼容性约束（对历史插件作者）：</p>
 * <ul>
 *   <li>该接口仍保持二进制兼容，但不再是推荐的 public extension API。</li>
 *   <li>新增能力优先通过 {@code default} 方法扩展，避免破坏既有实现。</li>
 *   <li>插件迁移到 restricted SPI 后，只能注册命令，不能直接操作 core 内部服务。</li>
 * </ul>
 */
public interface CommandProvider {
    String getName();

    default CommandProviderInfo getInfo() {
        return CommandProviderInfo.legacy(getName());
    }

    default Collection<CommandDescriptor> getCommandDescriptors(CommandProviderContext context) {
        Map<String, Command> commands = getCommands();
        if (commands == null || commands.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, CommandMeta> metaByName = getCommandMeta();
        Collection<CommandDescriptor> descriptors = new ArrayList<>(commands.size());
        for (Map.Entry<String, Command> entry : commands.entrySet()) {
            if (entry == null) {
                continue;
            }
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            String normalized = name.trim().toLowerCase();
            CommandMeta meta = metaByName != null ? metaByName.get(normalized) : null;
            if (meta == null && metaByName != null) {
                meta = metaByName.get(name);
            }
            descriptors.add(CommandDescriptor.of(name, entry.getValue(), meta));
        }
        return descriptors;
    }

    default Map<String, Command> getCommands() {
        return Collections.emptyMap();
    }

    default Map<String, CommandMeta> getCommandMeta() {
        return Collections.emptyMap();
    }
}
