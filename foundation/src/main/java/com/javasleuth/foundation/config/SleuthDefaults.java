package com.javasleuth.foundation.config;

import com.javasleuth.foundation.config.schema.ConfigKey;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import java.util.Properties;

/**
 * Java-Sleuth 的集中默认配置。
 *
 * <p>该类用于把“默认值”的事实来源收敛到单处，避免在多个位置手写默认导致漂移。</p>
 *
 * <p>注意：默认资源 {@code /sleuth-default.properties} 仍是运维可读的 SSOT；
 * 当默认资源无法加载时，{@link ConfigLoader} 会回退到这里的默认集合。</p>
 */
public final class SleuthDefaults {
    private SleuthDefaults() {
    }

    public static void apply(Properties properties) {
        if (properties == null) {
            return;
        }

        for (ConfigKey<?> key : SleuthConfigSchema.keys()) {
            if (key == null) {
                continue;
            }
            properties.setProperty(key.getKey(), key.getLiteralDefaultValue());
        }
    }
}
