# 🚀 Install Clojure CLI

## macOS Installation

### Option 1: Using Homebrew (Recommended)
```bash
# Install Homebrew first if needed
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Install Clojure CLI tools
brew install clojure/tools/clojure
```

### Option 2: Direct Install Script
```bash
# Download and run official installer
curl -O https://download.clojure.org/install/posix-install-1.11.1.1435.sh
chmod +x posix-install-1.11.1.1435.sh
sudo ./posix-install-1.11.1.1435.sh
```

## Verify Installation
```bash
clj --version
# Should show: Clojure CLI version 1.11.1.1435
```

## Quick Test
```bash
cd /Users/darwinborges/finance-clj
clj -M -e "(println \"Clojure works!\")"
```

## Next Steps After Installation

1. **Test compilation:**
   ```bash
   clj -M -e "(require 'finance.core-datomic)" -e "(println \"✓ Compiled successfully\")"
   ```

2. **Import 4,877 transactions:**
   ```bash
   clj -M -m scripts.import-all-sources
   ```

3. **Explore in REPL:**
   ```bash
   clj -M:repl
   ```
   Then:
   ```clojure
   (require '[finance.core-datomic :as finance])
   (finance/init!)
   (finance/count-transactions)
   ```
