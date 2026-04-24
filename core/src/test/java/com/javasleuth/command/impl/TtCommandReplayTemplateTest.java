package com.javasleuth.core.command.impl;

import com.javasleuth.bootstrap.data.TtRecord;
import org.junit.Assert;
import org.junit.Test;

public class TtCommandReplayTemplateTest {

    @Test
    public void replayTemplateShouldNotContainTodoPlaceholder() throws Exception {
        TtRecord record = new TtRecord();
        record.setRecordId(1L);
        record.setTimestampMs(System.currentTimeMillis());
        record.setThreadName("main");
        record.setThreadId(1L);
        record.setClassName("com.example.Foo");
        record.setMethodName("bar");
        record.setMethodDescriptor("(Ljava/lang/String;I)Ljava/lang/String;");
        record.setDuration(1000L);
        record.setEventType(TtRecord.EventType.METHOD_EXIT);
        record.setParameters(new Object[] { "x", Integer.valueOf(1) });
        record.setReturnValue("y");

        String out = new com.javasleuth.core.command.impl.tt.TtReplayTemplateGenerator().generate(record);

        Assert.assertNotNull(out);
        Assert.assertFalse(out.contains("TODO"));
        Assert.assertTrue(out.contains("无法自动定位实例"));
    }
}
