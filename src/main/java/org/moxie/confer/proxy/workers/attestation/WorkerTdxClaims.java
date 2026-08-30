package org.moxie.confer.proxy.workers.attestation;

record WorkerTdxClaims(String mrtd,
                       String rtmr0,
                       String rtmr1,
                       String rtmr2,
                       String reportData,
                       boolean debuggable,
                       boolean migratable)
{
}
