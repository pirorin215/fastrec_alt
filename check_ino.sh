#!/bin/bash

echo "--- 関数のプロトタイプ宣言 ---"
grep -n -E '^\s*(static\s+|inline\s+)?([a-zA-Z_][a-zA-Z0-9_]*\s+){1,2}[a-zA-Z_][a-zA-Z0-9_]*\s*\([^)]*\)\s*;' *.ino *.h | grep -v '{' | grep -v '{' | grep -E -v '^[ \t]*return[ \t]+.+'
echo "--- extern 宣言 ---------------"
grep -n -E "^extern" *.ino *.h
echo "--- String 型利用 ---------------"
grep -n -E "[^a-zA-Z]String[^a-zA-Z]" *.ino *.h | grep -v "StringCheck:allows"
echo "--- vector 検索 ---------------"
grep vector *.ino *.h | grep -v "VectorCheck:allows"
echo "--- log_i 検索 ---------------"
grep log_i *.ino *.h
echo "--- 2行以上の空白 ---------------"

# --- find_empty_lines.sh の内容を統合 ---

check_empty_lines() {
    # Find all .ino files in the current directory and its subdirectories
    find . -name "*.ino" | while read -r file; do
        # Use awk to detect 2 or more consecutive empty lines
        awk '
            /^$/ {
                empty_lines++
            }
            /./ {
                if (empty_lines >= 2) {
                    print FILENAME ": " (NR - empty_lines) "行目 連続した空行"
                }
                empty_lines = 0
            }
            END {
                if (empty_lines >= 2) {
                    print FILENAME ": ファイルの終わりに連続した空行"
                }
            }
        ' "$file"
    done
}

check_empty_lines
