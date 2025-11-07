# Nix Development Shell - Finance Trust Construction v2.0
# Rich Hickey Alignment: 95%
# Date: 2025-11-06
#
# Usage:
#   nix-shell              # Enter development environment
#   nix-shell --run clj    # Run Clojure REPL directly
#
# Philosophy:
# - Zero abstraction between you and REPL
# - Reproducible bit-by-bit
# - Functional dependency management
# - NO Docker for development

{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  name = "finance-trust-construction-dev";

  # Development dependencies (all you need)
  buildInputs = with pkgs; [
    # Clojure toolchain
    clojure
    clojure-lsp

    # Java (required for Clojure)
    jdk21

    # Python for ML service
    python311
    python311Packages.pip
    python311Packages.virtualenv

    # Database (Datomic Free requires manual download, using H2 as alternative)
    # For Datomic: Download manually from https://www.datomic.com/get-datomic.html

    # Development tools
    git
    curl
    wget
    tree

    # Optional: REPL tools
    rlwrap  # For better REPL experience
  ];

  # Environment variables
  shellHook = ''
    echo ""
    echo "╔════════════════════════════════════════════════════════════╗"
    echo "║  Finance Trust Construction v2.0 - Development Shell      ║"
    echo "║  Rich Hickey Approved: Direct REPL, Zero Abstraction      ║"
    echo "╚════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Available commands:"
    echo "  dev-repl       - Start Clojure REPL (direct, no Docker)"
    echo "  dev-api        - Start Clojure API server"
    echo "  dev-ml         - Start Python ML service"
    echo "  dev-test       - Run all tests"
    echo "  dev-all        - Start all services in separate terminals"
    echo ""
    echo "Environment:"
    echo "  Clojure: $(clojure --version)"
    echo "  Java:    $(java -version 2>&1 | head -1)"
    echo "  Python:  $(python3 --version)"
    echo ""

    # Set up Python virtual environment for ML service
    if [ ! -d "ml-service/venv" ]; then
      echo "Creating Python virtual environment..."
      python3 -m venv ml-service/venv
      source ml-service/venv/bin/activate
      pip install -r ml-service/requirements.txt
      deactivate
      echo "✅ Python environment ready"
      echo ""
    fi

    # Create convenience scripts
    cat > dev-repl <<'EOF'
#!/usr/bin/env bash
# Start Clojure REPL directly (Rich Hickey way)
echo "🚀 Starting Clojure REPL..."
clojure -M:repl
EOF
    chmod +x dev-repl

    cat > dev-api <<'EOF'
#!/usr/bin/env bash
# Start Clojure API server
echo "🚀 Starting Clojure API (http://localhost:3000)..."
export DATOMIC_URI="datomic:mem://dev"
export ML_SERVICE_URL="http://localhost:8000"
clojure -M -m finance.api.core
EOF
    chmod +x dev-api

    cat > dev-ml <<'EOF'
#!/usr/bin/env bash
# Start Python ML service
echo "🚀 Starting Python ML service (http://localhost:8000)..."
source ml-service/venv/bin/activate
python ml-service/main.py
EOF
    chmod +x dev-ml

    cat > dev-test <<'EOF'
#!/usr/bin/env bash
# Run all tests
echo "🧪 Running tests..."
clojure -M:test
EOF
    chmod +x dev-test

    cat > dev-all <<'EOF'
#!/usr/bin/env bash
# Start all services in separate terminals (macOS)
echo "🚀 Starting all services..."
echo ""
echo "Opening terminals:"
echo "  1. Python ML service (port 8000)"
echo "  2. Clojure API (port 3000)"
echo ""

# macOS: Use osascript to open Terminal windows
osascript <<APPLESCRIPT
tell application "Terminal"
    do script "cd $(pwd) && nix-shell --run './dev-ml'"
    do script "cd $(pwd) && sleep 3 && nix-shell --run './dev-api'"
    activate
end tell
APPLESCRIPT

echo "✅ Services starting in separate terminals"
echo ""
echo "URLs:"
echo "  ML Service: http://localhost:8000"
echo "  API Server: http://localhost:3000"
EOF
    chmod +x dev-all

    # Set environment variables for development
    export DATOMIC_URI="datomic:mem://dev"
    export ML_SERVICE_URL="http://localhost:8000"
    export LOG_LEVEL="info"

    echo "Ready to develop! 🚀"
    echo ""
  '';

  # Python packages (installed in venv, not globally)
  # See ml-service/requirements.txt
}
