# The Packer image AMI builds run in when the profile's SSH transport is `ssm`: the stock image plus
# the AWS Session Manager plugin, which Packer's `ssh_interface = "session_manager"` needs on PATH.
#
# Built locally on first use and tagged by a hash of this file, so editing it triggers a rebuild.
FROM hashicorp/packer:full

# The plugin ships for Linux only as a glibc .deb. Alpine has no dpkg, so the binary is unpacked
# with ar and tar, and gcompat supplies the glibc symbols it links against. The architecture comes
# from uname rather than TARGETARCH, which the classic build API does not reliably set.
RUN set -eux; \
    case "$(uname -m)" in \
      x86_64) plugin_dir=ubuntu_64bit ;; \
      aarch64) plugin_dir=ubuntu_arm64 ;; \
      *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    apk add --no-cache gcompat; \
    apk add --no-cache --virtual .unpack binutils; \
    mkdir /tmp/session-manager-plugin; \
    cd /tmp/session-manager-plugin; \
    wget -q -O plugin.deb "https://s3.amazonaws.com/session-manager-downloads/plugin/latest/${plugin_dir}/session-manager-plugin.deb"; \
    ar x plugin.deb; \
    tar -xf data.tar.*; \
    install -m 0755 usr/local/sessionmanagerplugin/bin/session-manager-plugin /usr/local/bin/session-manager-plugin; \
    cd /; \
    rm -rf /tmp/session-manager-plugin; \
    apk del .unpack; \
    session-manager-plugin --version
