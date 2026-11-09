# Installation

```bash
git clone https://github.com/inevitable-lettus/Terminus.git
cd Terminus
./gradlew test       # optional: 313 tests against saved sandbox responses, nothing running
./bus help           # builds on first use, then prints every command and option
```

`./bus` rebuilds automatically whenever the sources changed, so there is no separate
build step.

## The dashboard (optional)

```bash
cd dashboard
npm install
npm run build
```

`./bus serve` then serves it at `http://127.0.0.1:8080`. Without a build, the API still
works and the server says the dashboard has not been built. See
[The dashboard](../guide/dashboard.md).

## The documentation

This site is built with MkDocs:

```bash
python3 -m venv .venv-docs
.venv-docs/bin/pip install -r docs/requirements.txt
.venv-docs/bin/mkdocs serve           # http://127.0.0.1:8000
.venv-docs/bin/mkdocs build --strict  # fails on any warning
```

## A sandbox

You also need a sandbox to read from. Installation of DSTNS, the supported one, is on the
[DSTNS integration page](../integrations/dstns/index.md#install).
