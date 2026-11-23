FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal

# AWS publishes no image. The package is pinned by version and checksum, both checked against its GPG signature
# (key 98BC F336 A3BE 53B7 34E9 67C5 2E5B F49B D940 F624) when they were taken.
ARG VERSION=1.0.583-1
ARG SHA256_X86_64=4be0b40c68e402c3a5e107099fa8ff6344f5cf78d8871eaff48bcf931e4e0d63
ARG SHA256_AARCH64=b30e151de3b1fa205b7980c66f25e835797ba7b92eafa0d48dbcce212586c8f6

RUN arch="$(uname -m)" \
    && case "$arch" in x86_64) sum="$SHA256_X86_64" ;; aarch64) sum="$SHA256_AARCH64" ;; *) exit 1 ;; esac \
    && curl -fsSLo /tmp/aws-emulator.rpm "https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/aws-emulator-$VERSION-$arch.rpm" \
    && echo "$sum  /tmp/aws-emulator.rpm" | sha256sum -c - \
    && rpm -i /tmp/aws-emulator.rpm \
    && rm /tmp/aws-emulator.rpm \
    && mkdir -p /home/emulator \
    && chown 1000 /home/emulator

USER 1000
ENV HOME=/home/emulator
EXPOSE 9297
ENTRYPOINT ["aws-emulator", "--bind", "0.0.0.0", "--telemetry", "off"]
