package com.printair.document;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversionWorkerTest {
    @Test
    void killsAndReportsConverterTimeout() {
        var process = new Process() {
            @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public boolean waitFor(long timeout, TimeUnit unit) { return false; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
            @Override public Process destroyForcibly() { return this; }
        };

        assertThatThrownBy(() -> ConversionWorker.awaitProcess(process))
                .isInstanceOf(TimeoutException.class)
                .hasMessage("Document conversion timed out");
    }
}
