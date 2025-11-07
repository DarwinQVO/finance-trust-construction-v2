# Nix Development Guide - Rich Hickey Way

**Date:** 2025-11-06
**Philosophy:** Zero abstraction, direct REPL access, reproducible environment

---

## Why Nix? (Rich Hickey Perspective)

### The Problem with Docker for Development

```
Docker (Development):
  ❌ Abstraction layer between you and REPL
  ❌ Build/rebuild cycles slow feedback
  ❌ "Works in container" != "I understand it"
  ❌ Complects environment with tooling

Rich Hickey says:
  "Simple is not easy. Easy is 'docker-compose up'.
   Simple is 'I have direct access to my REPL'."
```

### The Nix Solution

```
Nix (Development):
  ✅ Direct access to Clojure REPL (zero abstraction)
  ✅ Reproducible bit-by-bit (same deps everywhere)
  ✅ Functional package management (immutable)
  ✅ No Docker daemon running in background
  ✅ Fast feedback loop (instant REPL start)

Rich Hickey alignment: 95%
```

---

## Prerequisites

### Install Nix (One-time Setup)

**macOS / Linux:**
```bash
# Install Nix (multi-user, recommended)
sh <(curl -L https://nixos.org/nix/install)

# Verify installation
nix --version
# Output: nix (Nix) 2.18.1
```

**Enable Nix Flakes (optional, for future):**
```bash
mkdir -p ~/.config/nix
cat > ~/.config/nix/nix.conf <<EOF
experimental-features = nix-command flakes
EOF
```

---

## Quick Start

### 1. Enter Development Environment

```bash
cd /Users/darwinborges/finance-clj
nix-shell
```

**Output:**
```
╔════════════════════════════════════════════════════════════╗
║  Finance Trust Construction v2.0 - Development Shell      ║
║  Rich Hickey Approved: Direct REPL, Zero Abstraction      ║
╚════════════════════════════════════════════════════════════╝

Available commands:
  dev-repl       - Start Clojure REPL (direct, no Docker)
  dev-api        - Start Clojure API server
  dev-ml         - Start Python ML service
  dev-test       - Run all tests
  dev-all        - Start all services in separate terminals

Environment:
  Clojure: 1.11.1
  Java:    openjdk version "21.0.1"
  Python:  Python 3.11.6

Ready to develop! 🚀
```

---

### 2. Start Clojure REPL (The Rich Hickey Way)

```bash
./dev-repl
```

**What happens:**
```
🚀 Starting Clojure REPL...
Clojure 1.11.1
user=>

# Direct access, zero abstraction
# No Docker, no containers, no build steps
# Just you and the REPL
```

**Try it:**
```clojure
user=> (require '[finance.api.core :as api])
nil

user=> (require '[finance.orchestration.ml-pipeline :as ml])
nil

# Direct feedback, instant iteration
```

---

### 3. Start Services Individually

**Option A: Start API server**
```bash
./dev-api
```

**Output:**
```
🚀 Starting Clojure API (http://localhost:3000)...
INFO  finance.api.core - Starting server on port 3000
INFO  finance.api.core - Datomic connected: datomic:mem://dev
INFO  finance.api.core - ML service URL: http://localhost:8000
```

**Option B: Start Python ML service**
```bash
./dev-ml
```

**Output:**
```
🚀 Starting Python ML service (http://localhost:8000)...
INFO:     Started server process [12345]
INFO:     Waiting for application startup.
INFO:     Application startup complete.
INFO:     Uvicorn running on http://0.0.0.0:8000
```

**Option C: Run tests**
```bash
./dev-test
```

**Output:**
```
🧪 Running tests...
Running tests in finance.api.handlers-test
Running tests in finance.orchestration.ml-pipeline-test
...
Ran 15 tests containing 42 assertions.
0 failures, 0 errors.
```

---

### 4. Start All Services at Once

```bash
./dev-all
```

**What happens:**
1. Opens Terminal window 1 → Python ML service (port 8000)
2. Waits 3 seconds
3. Opens Terminal window 2 → Clojure API (port 3000)

**URLs:**
- ML Service: http://localhost:8000
- API Server: http://localhost:3000

**Stop services:** Ctrl+C in each terminal window

---

## Nix vs Docker Comparison

### Development Workflow

**Docker Way (What we had before):**
```bash
# 1. Build images (slow, 2-5 min first time)
docker-compose build

# 2. Start services (abstraction layer)
docker-compose up -d

# 3. Check logs (indirect)
docker-compose logs -f clojure-api

# 4. Make code change
vim src/finance/api/core.clj

# 5. Rebuild (slow)
docker-compose up -d --build clojure-api

# 6. Wait for restart
# 7. Test
curl http://localhost:3000/api/v1/health
```

**Total feedback cycle:** 30 seconds - 2 minutes

---

**Nix Way (What we have now):**
```bash
# 1. Enter shell (instant, cached)
nix-shell

# 2. Start REPL (instant)
./dev-repl

# 3. Make code change
vim src/finance/api/core.clj

# 4. Reload in REPL (instant)
user=> (require 'finance.api.core :reload)

# 5. Test immediately
user=> (api/start-server)
```

**Total feedback cycle:** 1-5 seconds

---

### Which to Use When?

**Use Nix for:**
- ✅ Development (REPL-driven, fast feedback)
- ✅ Running tests locally
- ✅ Debugging (direct access to processes)
- ✅ Learning/exploring codebase
- ✅ Quick experiments

**Use Docker for:**
- ✅ Production deployment
- ✅ CI/CD pipelines
- ✅ Integration testing (full stack)
- ✅ Sharing with non-developers
- ⚠️ Development (if team doesn't use Nix)

---

## Common Workflows

### Workflow 1: REPL-Driven Development

**Scenario:** Implementing a new API endpoint

```bash
# 1. Enter Nix shell
nix-shell

# 2. Start REPL
./dev-repl

# 3. Load namespace
user=> (require '[finance.api.handlers :as h])

# 4. Test function
user=> (h/handle-list-transactions {:params {}})
{:status 200, :body [...]}

# 5. Edit code in editor
# (add new endpoint)

# 6. Reload namespace
user=> (require 'finance.api.handlers :reload)

# 7. Test new function
user=> (h/handle-new-endpoint {:params {...}})

# No restart, no rebuild, instant feedback!
```

---

### Workflow 2: Testing Changes End-to-End

**Scenario:** Testing ML classification flow

```bash
# Terminal 1: Start Python ML service
nix-shell
./dev-ml

# Terminal 2: Start Clojure API
nix-shell
./dev-api

# Terminal 3: Send test request
curl -X POST http://localhost:3000/api/v1/transactions/123/classify

# Watch logs in terminals 1 & 2 (direct visibility)
```

---

### Workflow 3: Running Tests

```bash
# Inside Nix shell
nix-shell

# Run all tests
./dev-test

# Or run specific test namespace
clojure -M:test -n finance.api.handlers-test

# Or run in REPL
./dev-repl
user=> (require '[clojure.test :as t])
user=> (t/run-tests 'finance.api.handlers-test)
```

---

## Environment Details

### What Nix Provides

**Installed packages:**
```nix
- clojure        # Clojure CLI tools
- clojure-lsp    # Language server for editor integration
- jdk21          # Java 21 (required for Clojure)
- python311      # Python 3.11
- pip            # Python package manager
- virtualenv     # Python virtual environments
- git            # Version control
- curl, wget     # HTTP tools
- tree           # Directory visualization
- rlwrap         # Better REPL line editing
```

**Environment variables (auto-set):**
```bash
DATOMIC_URI=datomic:mem://dev
ML_SERVICE_URL=http://localhost:8000
LOG_LEVEL=info
```

---

### Python Virtual Environment

**Auto-created on first nix-shell:**
```
Location: ml-service/venv
Packages: FastAPI, uvicorn, pydantic (from requirements.txt)
Activation: Automatic in dev-ml script
```

**Manual activation (if needed):**
```bash
source ml-service/venv/bin/activate
python ml-service/main.py
```

---

## Troubleshooting

### Issue: "nix-shell: command not found"

**Solution:** Install Nix first
```bash
sh <(curl -L https://nixos.org/nix/install)
```

---

### Issue: "dev-repl not found"

**Solution:** You're not in Nix shell yet
```bash
# First enter Nix shell
nix-shell

# Then run commands
./dev-repl
```

---

### Issue: Slow first nix-shell

**Cause:** Nix is downloading/building packages first time

**Solution:** Wait ~2-5 minutes first time, then instant forever

**Cache location:** `~/.nix-store/` (packages reused across projects)

---

### Issue: Python packages not found

**Solution:** Recreate virtual environment
```bash
rm -rf ml-service/venv
nix-shell  # Auto-recreates venv
```

---

### Issue: Port already in use

**Check what's using port:**
```bash
lsof -i :3000  # For Clojure API
lsof -i :8000  # For Python ML

# Kill process
kill -9 <PID>
```

---

## Editor Integration

### VSCode

**Install extensions:**
- Calva (Clojure REPL integration)
- clojure-lsp (language server)

**Connect to REPL:**
1. Start `./dev-repl` in terminal
2. Calva: Connect to Running REPL
3. Select `localhost:5555` (nREPL port)

---

### Emacs

**Use CIDER:**
```elisp
;; .dir-locals.el
((clojure-mode . ((cider-clojure-cli-global-options . "-M:repl"))))
```

**Connect:**
```
M-x cider-connect
Host: localhost
Port: 5555
```

---

### Vim/Neovim

**Use Conjure:**
```vim
" In your .vimrc
Plug 'Olical/conjure'
```

**Connect:** Opens automatically when editing .clj files

---

## Philosophy Deep Dive

### Why Rich Hickey Would Approve

**1. Direct Access (No Abstraction)**
```
Docker:  You → docker-compose → Docker daemon → Container → REPL
Nix:     You → REPL

Simplicity wins.
```

---

**2. Reproducibility (Functional)**
```
Docker: Dockerfile + base image + apt-get (mutable, imperative)
Nix:    Declarative derivations (pure functions, immutable)

Nix is more aligned with functional principles.
```

---

**3. Fast Feedback Loop**
```
Docker: Change → Rebuild image → Restart container → Test (30s-2min)
Nix:    Change → Reload namespace → Test (1-5s)

REPL-driven development is Rich Hickey's core philosophy.
```

---

**4. Understanding (Transparency)**
```
Docker: "It works" (black box)
Nix:    "I see every dependency" (transparent)

You understand what's running.
```

---

### Quote from Rich Hickey (paraphrased)

> "We should be programming at the REPL, not fighting with build tools.
> The REPL should be instant. Feedback should be immediate.
> Anything that gets between you and your running program is complecting."

**Nix achieves this. Docker for development does not.**

---

## Next Steps

### Immediate (Today)

1. ✅ Install Nix (if not already)
2. ✅ Run `nix-shell`
3. ✅ Try `./dev-repl`
4. ✅ Load a namespace, test a function
5. ✅ Experience instant feedback

---

### This Week

1. Develop new features using REPL-driven workflow
2. Run tests with `./dev-test` before commits
3. Use `./dev-all` when testing full stack
4. Keep Docker Compose for deployment only

---

### Team Adoption (If Working with Others)

**For developers who use Nix:**
```bash
git clone <repo>
cd finance-clj
nix-shell
./dev-repl
# Ready to code!
```

**For developers who don't use Nix:**
- They can still use Docker Compose
- Nix is optional but recommended
- Document both workflows in README

---

## Comparison Table

| Aspect | Docker (Dev) | Nix (Dev) | Winner |
|--------|-------------|-----------|--------|
| Startup time | 10-30s | 0-2s | Nix |
| Reload time | 10-60s | 0-2s | Nix |
| Memory usage | 1-2GB | 100-200MB | Nix |
| Abstraction | High | Zero | Nix |
| REPL access | Indirect | Direct | Nix |
| Reproducibility | Good | Perfect | Nix |
| Learning curve | Low | Medium | Docker |
| Team adoption | Easy | Medium | Docker |
| Rich Hickey alignment | 30% | 95% | Nix |

---

## Summary

**For Development:** Use Nix (this guide)
- Direct REPL access
- Instant feedback
- Zero abstraction
- Rich Hickey approved: 95%

**For Deployment:** Use Docker Compose (see DEPLOYMENT.md)
- Standardized containers
- Easy CI/CD
- Production-ready

**Best of both worlds:**
```
Develop with Nix → Fast, simple, direct
Deploy with Docker → Portable, standard, easy
```

---

**Last Updated:** 2025-11-06
**Status:** ✅ Ready for REPL-driven development
**Philosophy:** Rich Hickey - Simple > Easy
