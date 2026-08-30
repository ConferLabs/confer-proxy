FROM maven:3.9.11-eclipse-temurin-25-noble

ADD --checksum=sha256:40352ecdd40af04b78d46e47808a48223bf745771a2ed313a83b1f191fb2625c \
    https://download.01.org/intel-sgx/sgx_repo/ubuntu/pool/main/libs/libsgx-dcap-quote-verify/libsgx-dcap-quote-verify_1.27.101.1-noble1_amd64.deb \
    /tmp/libsgx-dcap-quote-verify.deb
ADD --checksum=sha256:483c8892ad12bcb38ef7aa9c85a4a4bd28cd0c0b97e0a541e41f6f8381907097 \
    https://download.01.org/intel-sgx/sgx_repo/ubuntu/pool/main/libs/libsgx-dcap-default-qpl/libsgx-dcap-default-qpl_1.27.101.1-noble1_amd64.deb \
    /tmp/libsgx-dcap-default-qpl.deb

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        /tmp/libsgx-dcap-quote-verify.deb \
        /tmp/libsgx-dcap-default-qpl.deb \
    && rm -rf \
        /tmp/libsgx-dcap-quote-verify.deb \
        /tmp/libsgx-dcap-default-qpl.deb \
        /var/lib/apt/lists/*

COPY src/test/docker/dcap/sgx_default_qcnl.conf /etc/sgx_default_qcnl.conf

WORKDIR /workspace

ENTRYPOINT ["mvn", "--batch-mode", "-Pdcap-integration", "-Dtest=DcapWorkerQuoteVerifierIntegrationTest", "test"]
