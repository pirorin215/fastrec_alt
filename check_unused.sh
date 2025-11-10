#!/bin/bash

# ctagsとcscopeがインストールされているか確認
if ! command -v ctags &> /dev/null;
then
    echo "エラー: ctagsがインストールされていません。'brew install ctags'などでインストールしてください。"
    exit 1
fi
if ! command -v cscope &> /dev/null;
then
    echo "エラー: cscopeがインストールされていません。'brew install cscope'などでインストールしてください。"
    exit 1
fi

# --- メイン処理 ---
# 解析対象のソースファイル一覧を取得
# .ino, .h, .cpp, .c ファイルを対象とする
SOURCE_FILES=$(find . -maxdepth 1 -type f \( -name "*.ino" -o -name "*.h" -o -name "*.cpp" -o -name "*.c" \))
if [ -z "$SOURCE_FILES" ]; then
    echo "対象のソースファイルが見つかりません。"
    exit 0
fi

# cscope.filesを生成
find . -maxdepth 1 -type f \( -name "*.ino" -o -name "*.h" -o -name "*.cpp" -o -name "*.c" \) > cscope.files

# cscopeデータベースを生成 (-k: C言語のキーワードを無視, -b: ビルドのみ, -q: インデックス作成中のメッセージを抑制)
cscope -b -q -k

# ctagsでタグファイルを生成 (関数のみを対象、行番号も出力)
ctags --fields=+n --langmap=C++:+.ino --c++-kinds=f -o tags $SOURCE_FILES

# ctagsが成功したか確認
if [ ! -f "tags" ]; then
    echo "エラー: tagsファイルの生成に失敗しました。"
    exit 1
fi

# tagsファイルからシンボル名（1列目）を抽出
# !で始まる行はヘッダーなので除外
SYMBOLS=$(awk '!/^!/ && NF {print $1}' tags)

UNUSED_FUNCTIONS=""
HAS_UNUSED=0

# Arduinoの標準関数など、チェックから除外する関数
EXCLUDE_LIST="setup loop main"

for symbol in $SYMBOLS; do
    # 除外リストにあるシンボルはスキップ
    is_excluded=0
    for excluded in $EXCLUDE_LIST; do
        if [ "$symbol" == "$excluded" ]; then
            is_excluded=1
            break
        fi
    done
    if [ "$is_excluded" -eq 1 ]; then
        continue
    fi

    # cscopeでこの関数を呼び出している関数を検索し、grepで完全一致か確認
    CALLERS=$(cscope -d -L3 "${symbol}" | grep -w "${symbol}")

    # 呼び出し元がいない場合、未使用の可能性がある
    if [ -z "$CALLERS" ]; then
        # --- 無視コメントのチェック ---
        tag_line=$(grep -w "^${symbol}\b" tags)
        
        # タグ情報からファイル名と行番号を取得
        filename=$(echo "$tag_line" | awk -F'\t' '{print $2}')
        line_number=$(echo "$tag_line" | awk -F'\t' '{for(i=4;i<=NF;i++) if($i ~ /^line:/) {sub("line:", "", $i); print $i}}')

        if [ -z "$line_number" ]; then
            continue # 行番号がなければスキップ
        fi

        # ソースファイルから行番号で定義行を取得
        definition_content=$(sed -n "${line_number}p" "$filename")

        # 定義行に "check_unused:ignore" が含まれていればスキップ
        if echo "$definition_content" | grep -q "check_unused:ignore"; then
            continue
        fi

        # --- 未使用関数として報告 ---
        # awkで基本情報を抽出し、sedで整形する
        definition_line_formatted=$(echo "$tag_line" | 
            awk -F'\t' '{print $2 " : " $3}' | 
            sed -e 's/ : \/\^/ : /' -e 's/{\$\/;"$//' -e 's/\$\/;"$//' |
            sed -e 's/\s*$//') # Trim trailing whitespace
        UNUSED_FUNCTIONS+="${definition_line_formatted}\n"
        HAS_UNUSED=1
    fi
done

# 一時ファイルを削除
rm -f cscope.files cscope.out cscope.in.out cscope.po.out tags

echo "----------------------------------------"
if [ "$HAS_UNUSED" -eq 0 ]; then
    echo "未使用の関数は見つかりませんでした。"
else
    printf "%b" "未使用の可能性のある関数:\n"
    printf "%b" "$UNUSED_FUNCTIONS"
    printf "%b" "\n注意: このチェックは完全ではありません。\n"
    printf "%b" "関数ポインタ経由での呼び出しなどは検出できず、誤って「未使用」と判定される可能性があります。\n"
    printf "%b" "誤検出された関数は、定義行に 'check_unused:ignore' というコメントを追加することで無視できます。\n"
fi
echo "----------------------------------------"
