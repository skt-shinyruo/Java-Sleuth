package com.javasleuth.core.command.pipeline;

import com.javasleuth.core.command.CommandArgs;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.core.command.CommandRegistry;
import com.javasleuth.foundation.security.AuthorizationManager;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.InputValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 命令 precheck 阶段（validate/authz/confirm）显式化。
 *
 * <p>该组件不做命令执行，仅判断是否允许执行并输出 normalized args。</p>
 */
public final class PrecheckPipeline {
    private final List<PrecheckStep> steps;

    public PrecheckPipeline(
        InputValidator inputValidator,
        AuthorizationManager authorizationManager,
        DangerousCommandConfirmationManager dangerousConfirm
    ) {
        List<PrecheckStep> s = new ArrayList<>(3);
        s.add(new ValidateStep(inputValidator));
        s.add(new BootstrapBridgeStep());
        s.add(new AuthorizationStep(authorizationManager));
        s.add(new DangerousConfirmStep(dangerousConfirm));
        this.steps = s;
    }

    public PrecheckDecision precheck(CommandRegistry.Entry entry, String commandName, String[] rawArgs, CommandContext context) {
        String[] argsForChecks = CommandArgs.stripConfirmArgs(rawArgs);
        CommandMeta meta = entry != null ? entry.getMeta() : null;

        PrecheckState state = new PrecheckState(entry, meta, commandName, rawArgs, argsForChecks, context);
        for (PrecheckStep step : steps) {
            PrecheckDecision decision = step.apply(state);
            if (decision != null && !decision.isOk()) {
                return decision;
            }
        }

        String[] normalized = state.normalizedArgs != null ? state.normalizedArgs : rawArgs;
        return PrecheckDecision.ok(normalized);
    }

    private interface PrecheckStep {
        PrecheckDecision apply(PrecheckState state);
    }

    private static final class PrecheckState {
        private final CommandRegistry.Entry entry;
        private final CommandMeta meta;
        private final String commandName;
        private final String[] rawArgs;
        private final String[] argsForChecks;
        private final CommandContext context;
        private String[] normalizedArgs;

        private PrecheckState(
            CommandRegistry.Entry entry,
            CommandMeta meta,
            String commandName,
            String[] rawArgs,
            String[] argsForChecks,
            CommandContext context
        ) {
            this.entry = entry;
            this.meta = meta;
            this.commandName = commandName;
            this.rawArgs = rawArgs;
            this.argsForChecks = argsForChecks;
            this.context = context;
        }
    }

    private static final class ValidateStep implements PrecheckStep {
        private final InputValidator inputValidator;

        private ValidateStep(InputValidator inputValidator) {
            this.inputValidator = inputValidator;
        }

        @Override
        public PrecheckDecision apply(PrecheckState state) {
            if (state == null || state.context == null) {
                return PrecheckDecision.denied("Invalid command context", state != null ? state.argsForChecks : null);
            }
            InputValidator.ValidationResult validation = inputValidator.validateCommand(
                state.context.getClientId(),
                state.context.getClientInfo(),
                state.commandName,
                state.argsForChecks
            );
            if (validation != null && !validation.isValid()) {
                return PrecheckDecision.denied("Security validation failed: " + validation.getMessage(), state.argsForChecks);
            }
            return null;
        }
    }

    private static final class AuthorizationStep implements PrecheckStep {
        private final AuthorizationManager authorizationManager;

        private AuthorizationStep(AuthorizationManager authorizationManager) {
            this.authorizationManager = authorizationManager;
        }

        @Override
        public PrecheckDecision apply(PrecheckState state) {
            if (state == null) {
                return PrecheckDecision.denied("Invalid command context", null);
            }

            if (!"auth".equals(state.commandName)) {
                AuthorizationManager.AuthorizationResult authz = authorizationManager.authorize(
                    state.context.getSessionId(),
                    state.commandName,
                    state.argsForChecks,
                    state.meta
                );
                if (authz != null && !authz.isAllowed()) {
                    return PrecheckDecision.denied(authz.getReason(), state.argsForChecks);
                }
            }
            return null;
        }
    }

    /**
     * Prevent enabling enhancers that would inject bootstrap calls when the bootstrap bridge is unavailable.
     *
     * <p>This is a safety gate: failing to meet the bootstrap visibility precondition may crash the target
     * application at runtime (NoClassDefFoundError/LinkageError) when the enhanced bytecode executes.</p>
     */
    private static final class BootstrapBridgeStep implements PrecheckStep {
        @Override
        public PrecheckDecision apply(PrecheckState state) {
            if (state == null) {
                return null;
            }
            String name = state.commandName;
            if (name == null) {
                return null;
            }

            String cmd = name.trim().toLowerCase(Locale.ROOT);
            String required = requiredBootstrapClassForCommand(cmd);
            if (required == null) {
                return null;
            }

            // Allow help output even when enhancements are disabled.
            if (isHelpLikeInvocation(state.argsForChecks)) {
                return null;
            }

            if (BootstrapBridge.canEnableEnhancement(required, null)) {
                return null;
            }

            return PrecheckDecision.denied(BootstrapBridge.formatDisabledMessage(cmd, required), state.argsForChecks);
        }

        private static String requiredBootstrapClassForCommand(String cmd) {
            if (cmd == null) {
                return null;
            }
            switch (cmd) {
                case "watch":
                    return BootstrapBridge.WATCH_INTERCEPTOR;
                case "trace":
                    return BootstrapBridge.TRACE_INTERCEPTOR;
                case "monitor":
                    return BootstrapBridge.MONITOR_INTERCEPTOR;
                case "tt":
                    return BootstrapBridge.TT_INTERCEPTOR;
                case "stack":
                    return BootstrapBridge.STACK_INTERCEPTOR;
                case "vmtool":
                    return BootstrapBridge.VMTOOL_INTERCEPTOR;
                default:
                    return null;
            }
        }

        private static boolean isHelpLikeInvocation(String[] args) {
            if (args == null) {
                return false;
            }
            if (args.length <= 1) {
                return true;
            }
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a == null) {
                    continue;
                }
                String s = a.trim().toLowerCase(Locale.ROOT);
                if ("-h".equals(s) || "--help".equals(s) || "help".equals(s)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class DangerousConfirmStep implements PrecheckStep {
        private final DangerousCommandConfirmationManager dangerousConfirm;

        private DangerousConfirmStep(DangerousCommandConfirmationManager dangerousConfirm) {
            this.dangerousConfirm = dangerousConfirm;
        }

        @Override
        public PrecheckDecision apply(PrecheckState state) {
            if (state == null) {
                return PrecheckDecision.denied("Invalid command context", null);
            }

            DangerousCommandConfirmationManager.ConfirmationResult confirm =
                dangerousConfirm.confirmIfRequired(
                    state.context.getSessionId(),
                    state.context.getClientInfo(),
                    state.commandName,
                    state.rawArgs,
                    state.meta
                );
            if (confirm != null && !confirm.isAllowed()) {
                String[] argsForReturn = confirm.getNormalizedArgs() != null ? confirm.getNormalizedArgs() : state.rawArgs;
                return PrecheckDecision.denied(confirm.getError(), argsForReturn);
            }

            state.normalizedArgs = confirm != null ? confirm.getNormalizedArgs() : null;
            return null;
        }
    }
}
