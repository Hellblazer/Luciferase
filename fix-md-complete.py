#!/usr/bin/env python3
"""
Complete markdown linting fix for remaining files.
Handles all MD032, MD031, MD040, MD060, MD022 violations.
"""

import sys
import re

def fix_markdown_complete(content):
    """Fix all markdown linting issues."""
    lines = content.split('\n')
    
    # First pass: Fix individual lines
    for i in range(len(lines)):
        line = lines[i]
        
        # MD040: Add language to bare code fences
        if line.strip() == '```':
            lines[i] = '```text'
        
        # MD060: Fix table separators
        if re.match(r'^\|[-:| ]+\|$', line):
            parts = line.split('|')
            fixed_parts = []
            for j, part in enumerate(parts):
                if j == 0 or j == len(parts) - 1:
                    fixed_parts.append(part)
                else:
                    cleaned = part.strip().replace(' ', '')
                    fixed_parts.append(f' {cleaned} ')
            lines[i] = '|'.join(fixed_parts)
    
    # Second pass: Add blank lines where needed
    result = []
    for i, line in enumerate(lines):
        prev_line = lines[i - 1] if i > 0 else ''
        next_line = lines[i + 1] if i < len(lines) - 1 else ''
        
        # Classify lines - FIXED: Use proper regex for list detection
        is_heading = line.startswith('#') and len(line) > 1 and line[1] in (' ', '#')
        # Match unordered lists (- or * with space) or ordered lists (number. with space)
        is_list = bool(re.match(r'^(\s*)[-*]\s+|^(\s*)\d+\.\s+', line))
        is_code_fence = line.strip().startswith('```')
        is_blank = not line.strip()
        
        prev_is_heading = prev_line.startswith('#') and len(prev_line) > 1 and prev_line[1] in (' ', '#')
        prev_is_list = bool(re.match(r'^(\s*)[-*]\s+|^(\s*)\d+\.\s+', prev_line))
        prev_is_code = prev_line.strip().startswith('```')
        prev_is_blank = not prev_line.strip()
        
        next_is_heading = next_line.startswith('#') and len(next_line) > 1 and next_line[1] in (' ', '#')
        next_is_list = bool(re.match(r'^(\s*)[-*]\s+|^(\s*)\d+\.\s+', next_line))
        next_is_blank = not next_line.strip()
        
        # MD022: Blank before heading (unless prev is blank or heading)
        if is_heading and not prev_is_blank and not prev_is_heading and prev_line.strip():
            result.append('')
        
        # MD032: Blank before list (unless prev is blank/list/heading/code)
        if is_list and not prev_is_blank and not prev_is_list and not prev_is_heading and not prev_is_code and prev_line.strip():
            result.append('')
        
        # MD031: Blank before code fence (unless prev is blank/heading)
        if is_code_fence and not prev_is_blank and not prev_is_heading and prev_line.strip():
            result.append('')
        
        # Add current line
        result.append(line)
        
        # MD022: Blank after heading (unless next is blank/heading)
        if is_heading and not next_is_blank and not next_is_heading and next_line.strip():
            result.append('')
        
        # MD032: Blank after list (unless next is blank/list/heading)
        if is_list and not next_is_blank and not next_is_list and not next_is_heading and next_line.strip():
            result.append('')
        
        # MD031: Blank after code fence (unless next is blank/heading)
        if is_code_fence and not next_is_blank and not next_is_heading and next_line.strip():
            result.append('')
    
    # Join and clean up
    text = '\n'.join(result)
    
    # MD012: Remove multiple consecutive blank lines
    text = re.sub(r'\n\n\n+', '\n\n', text)
    
    # MD047: Ensure single trailing newline
    text = text.rstrip('\n') + '\n'
    
    return text

if __name__ == '__main__':
    if len(sys.argv) != 2:
        print("Usage: fix-md-complete.py <file>")
        sys.exit(1)
    
    filepath = sys.argv[1]
    with open(filepath, 'r') as f:
        content = f.read()
    
    fixed = fix_markdown_complete(content)
    
    with open(filepath, 'w') as f:
        f.write(fixed)
    
    print(f"Fixed {filepath}")
