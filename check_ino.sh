#!/bin/bash

echo "--- 関数のプロトタイプ宣言 ---"
grep -h -E '^\s*(static\s+|inline\s+)?([a-zA-Z_][a-zA-Z0-9_]*\s+){1,2}[a-zA-Z_][a-zA-Z0-9_]*\s*\([^)]*\)\s*;' *.ino *.h | grep -v '{' | grep -v '{' | grep -E -v '^[ \t]*return[ \t]+.+'
echo "--- extern宣言 ---------------"
grep -h -E "^extern" *.ino *.h
echo "--- String型利用 ---------------"
grep -h -E "[^a-zA-Z]String[^a-zA-Z]" *.ino *.h
