package org.moxie.confer.proxy.workers;

import com.jcraft.jsch.ChannelExec;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkerProcessTest {

  @Test
  void closeEndsInputThenDisconnects() throws Exception {
    ChannelExec  channel = mock(ChannelExec.class);
    OutputStream input   = mock(OutputStream.class);

    new WorkerProcess(channel, new ByteArrayInputStream(new byte[0]), input).close();

    InOrder order = inOrder(input, channel);
    order.verify(input).close();
    order.verify(channel).disconnect();
  }

  @Test
  void closeDisconnectsWhenEndingInputFails() throws Exception {
    ChannelExec  channel = mock(ChannelExec.class);
    OutputStream input   = mock(OutputStream.class);
    doThrow(new IOException("closed")).when(input).close();

    new WorkerProcess(channel, new ByteArrayInputStream(new byte[0]), input).close();

    verify(channel).disconnect();
  }
}
