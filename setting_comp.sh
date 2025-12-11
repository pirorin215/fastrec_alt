#!/bin/bash

INI_FILE="setting.ini"
H_FILE="fastrec_alt.h"

#echo "Comparing values from $INI_FILE and $H_FILE"

# Read setting.ini line by line
while IFS='=' read -r ini_key ini_value; do
    # Skip empty lines
    if [[ -z "$ini_key" ]]; then
        continue
    fi




    # Trim whitespace from ini_value
    ini_value=$(echo "$ini_value" | xargs)

    # Search for the key in fastrec_alt.h
    # Matches: #define KEY VALUE, const TYPE KEY = VALUE;, or TYPE KEY = VALUE;
    # Simplified grep to be more robust against varying type declarations
    h_line=$(grep -E "(^#define[[:space:]]+${ini_key}[[:space:]]+|^.*[[:space:]]+${ini_key}[[:space:]]*=)" "$H_FILE")

    if [[ -z "$h_line" ]]; then
        # This warning is useful for debugging, but can be removed if the user doesn't want it.
        # For now, let's keep it as it indicates a potential issue with the .h file or the key name.
        echo "WARNING: Key '$ini_key' not found or not in expected format in $H_FILE"
        continue
    fi

    h_value=""
    # Extract value for #define
    if [[ "$h_line" == *#define* ]]; then
        h_value=$(echo "$h_line" | sed -E "s/^#define[[:space:]]+$ini_key[[:space:]]+(.*)/\1/")
    # Extract value for const TYPE KEY = VALUE; or TYPE KEY = VALUE;
    # Simplified sed to extract everything after the equals sign and before the semicolon
    elif [[ "$h_line" == *"$ini_key ="* ]]; then
        h_value=$(echo "$h_line" | sed -E "s/^.*${ini_key}[[:space:]]*=[[:space:]]*(.*);/\1/")
    fi

    # Trim whitespace from h_value
    h_value=$(echo "$h_value" | xargs)

    # Remove C++ style comments from h_value
    h_value=$(echo "$h_value" | sed -E 's_//.*__')
    # Trim whitespace again after removing comments, in case there was whitespace before the comment
    h_value=$(echo "$h_value" | xargs)

    # Remove (char*) cast from h_value if present.
    h_value=$(echo "$h_value" | sed -E 's/^\(char\*\)//')

    # Handle "string" in .h file (if it's a string literal) by removing quotes
    if echo "$h_value" | grep -qE '^\".*\"$'; then
        h_value=$(echo "$h_value" | sed -E 's/^\"(.*)\"$/\1/')
    fi

    # Normalize values for comparison
    normalized_ini_value="$ini_value"
    normalized_h_value="$h_value" # Assign the processed h_value to normalized_h_value

    # Remove 'f' suffix for float comparison if present
    if [[ "$normalized_ini_value" == *f ]]; then
        normalized_ini_value="${normalized_ini_value%f}"
    fi
    if [[ "$normalized_h_value" == *f ]]; then
        normalized_h_value="${normalized_h_value%f}"
    fi

    if [[ "$normalized_ini_value" != "$normalized_h_value" ]]; then
        # Changed output format as per user request
        printf "%-20s: %s -> %s\n" "$ini_key" "$h_value" "$ini_value"
    fi

done < "$INI_FILE"
