package org.moxie.confer.proxy.workers;

public record WorkerCommandResult(int     exitCode,
                                  String  output,
                                  boolean truncated)
{
}
