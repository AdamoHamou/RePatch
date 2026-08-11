# RePatch 2.0 — headless evaluation container (all-in-one: the pipeline,
# IntelliJ, and MySQL live in this single image; `docker run -it` drops
# into an interactive shell with the `repatch` CLI).
#
# Build from the REPO ROOT (the build context must be the repository, not
# this directory):
#   docker build -f containers/Dockerfile -t repatch-headless .
# or, preferred:
#   docker compose -f containers/docker-compose.yml build
#
# The IntelliJ 2024.3.7 distribution, the Gradle distribution, and all
# dependencies are baked into the image at build time so runs are offline
# apart from the one-time kafka clone (cached in a volume).

FROM eclipse-temurin:17-jdk-noble

ARG DEBIAN_FRONTEND=noninteractive
# mysql-server runs INSIDE this container (no sidecar); its packaged
# datadir is dropped — the real one is initialized in the data volume on
# first boot so databases persist across containers.
RUN apt-get update && apt-get install -y --no-install-recommends \
        git ca-certificates curl unzip tini less \
        xvfb libfreetype6 fontconfig fonts-dejavu-core \
        mysql-server mysql-client \
        python3 python3-matplotlib maven \
    && rm -rf /var/lib/apt/lists/* /var/lib/mysql

# JDK 11 serves two roles: the evaluation project's model pins
# project-jdk-name="11" (.idea/misc.xml, kafka-era) and IntelliJ's SDK
# detector auto-registers a JDK found under /usr/lib/jvm; and the
# entrypoint's `gradle idea` model regeneration runs kafka's own
# gradle, which predates JDK 17 support.
COPY --from=eclipse-temurin:11-jdk-noble /opt/java/openjdk /usr/lib/jvm/temurin-11-jdk-amd64
# JDK 8 is for the validation study's build/test stages only: the benchmark
# projects are of the paper's era (2015-2017 bitcoinj, DSpace 5/6, ...) and
# fail on a modern toolchain for reasons unrelated to the patch under test.
COPY --from=eclipse-temurin:8-jdk-noble /opt/java/openjdk /usr/lib/jvm/temurin-8-jdk-amd64

# Dedicated runtime user at uid 1000 (drop the stock ubuntu user that
# occupies the uid in noble-based images). The workdir is created and
# chowned explicitly: the legacy builder creates WORKDIR as root even
# under USER, which would leave gradle unable to write .gradle/.
RUN userdel -r ubuntu 2>/dev/null || true; useradd -m -u 1000 repatch \
    && mkdir -p /home/repatch/RePatch \
    && chown -R repatch:repatch /home/repatch
USER repatch
ENV HOME=/home/repatch
ENV PATH=/home/repatch/bin:$PATH
WORKDIR /home/repatch/RePatch

# git identity: the pipeline COMMITS (undo commits, evidence commits);
# without an identity `git commit` refuses, HEAD silently stays at the
# right commit, and every inversion is committed as a no-op (vacuous).
RUN git config --global user.name "RePatch Container" \
    && git config --global user.email "repatch@container.local"

# RefactoringMiner 2.1.0 resolves from the local Maven repo (flat POM,
# transitive=false — see build.gradle); bake it where mavenLocal() looks.
COPY --chown=repatch:repatch containers/assets/refactoring-miner-2.1.0.jar \
     containers/assets/refactoring-miner-2.1.0.pom \
     /home/repatch/.m2/repository/com/github/tsantalis/refactoring-miner/2.1.0/

# Bake Gradle + the IntelliJ Platform into the image in a source-free
# layer: with only the build scripts present, dependency resolution
# downloads the gradle distribution, ideaIC-2024.3.7, and every library.
# Source edits then invalidate only the cheap compile layer below, not
# this multi-gigabyte one.
COPY --chown=repatch:repatch gradlew build.gradle settings.gradle /home/repatch/RePatch/
COPY --chown=repatch:repatch gradle /home/repatch/RePatch/gradle/
COPY --chown=repatch:repatch lib /home/repatch/RePatch/lib/
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# The repository itself, then the (fast, offline) compile.
COPY --chown=repatch:repatch . /home/repatch/RePatch/
RUN ./gradlew --no-daemon compileJava compileTestJava; rc=$?; rm -rf /tmp/* 2>/dev/null || true; exit $rc

# The 43-module JPS project model for the kafka evaluation clone
# (.idea + *.iml overlay, regenerable via `gradle idea` — see README).
COPY --chown=repatch:repatch containers/assets/kafka-model-overlay.tar.gz /home/repatch/assets/
COPY --chown=repatch:repatch containers/entrypoint.sh /home/repatch/entrypoint.sh
COPY --chown=repatch:repatch containers/bin/ /home/repatch/bin/
# Strip CR in case the build context came from a CRLF checkout (Windows
# clones without the .gitattributes pin applied), then mark executable.
RUN sed -i 's/\r$//' /home/repatch/entrypoint.sh /home/repatch/bin/* \
    && chmod +x /home/repatch/entrypoint.sh /home/repatch/bin/repatch

# Clone/template cache and results are volumes (see docker-compose.yml).
# The mountpoints must pre-exist owned by the runtime user, or Docker
# creates them root-owned at mount time.
RUN mkdir -p /home/repatch/data /home/repatch/results
VOLUME ["/home/repatch/data", "/home/repatch/results"]

ENTRYPOINT ["/usr/bin/tini", "--", "/home/repatch/entrypoint.sh"]
