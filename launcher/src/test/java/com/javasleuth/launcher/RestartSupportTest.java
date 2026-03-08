package com.javasleuth.launcher;

import org.junit.Assert;
import org.junit.Test;

public class RestartSupportTest {

    @Test
    public void extractConfirmTokenBestEffort_shouldReturnTokenFromChallengeMessage() {
        String msg =
            "⚠️ 高风险命令需要二次确认（有效期 60s）。\n" +
                "请重新执行并追加：--confirm <token>\n" +
                "示例：\n" +
                "  stop --confirm abcDEF123_-";

        Assert.assertEquals("abcDEF123_-", RestartSupport.extractConfirmTokenBestEffort(msg));
    }

    @Test
    public void extractConfirmTokenBestEffort_shouldReturnLastTokenWhenMultiplePresent() {
        String msg =
            "stop --confirm token1\n" +
                "stop --confirm token2";

        Assert.assertEquals("token2", RestartSupport.extractConfirmTokenBestEffort(msg));
    }

    @Test
    public void looksLikeAuthIssue_shouldDetectCommonAuthErrors() {
        Assert.assertTrue(RestartSupport.looksLikeAuthIssue("Authentication required. Use: auth <user> <password>"));
        Assert.assertTrue(RestartSupport.looksLikeAuthIssue("Insufficient permissions. Required: ADMIN"));
        Assert.assertFalse(RestartSupport.looksLikeAuthIssue("Unknown command: stop"));
    }
}

