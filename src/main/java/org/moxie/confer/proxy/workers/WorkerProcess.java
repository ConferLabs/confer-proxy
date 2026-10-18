package org.moxie.confer.proxy.workers;

import com.jcraft.jsch.ChannelExec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A process running on a worker over its own SSH exec channel. Like
 * {@link Process}, the input stream is the process's standard output and the
 * output stream is its standard input. Standard error is discarded.
 *
 * <p>Read the input stream from a single thread for the life of the process:
 * JSch delivers output through a JDK pipe, which fails the channel once the
 * thread that last read from it has exited.</p>
 */
public class WorkerProcess implements AutoCloseable {

  private final ChannelExec  channel;
  private final InputStream  output;
  private final OutputStream input;

  WorkerProcess(ChannelExec  channel,
                InputStream  output,
                OutputStream input)
  {
    this.channel = channel;
    this.output  = output;
    this.input   = input;
  }

  public InputStream getInputStream() {
    return output;
  }

  public OutputStream getOutputStream() {
    return input;
  }

  /**
   * Ends the process's input so it can exit cleanly, then releases the
   * channel without waiting for it to do so.
   */
  @Override
  public void close() {
    try {
      input.close();
    } catch (IOException ignored) {
      // The channel is disconnected below either way.
    } finally {
      channel.disconnect();
    }
  }
}
