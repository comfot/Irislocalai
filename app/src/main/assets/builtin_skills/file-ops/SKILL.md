---
name: 文件操作
description: 读写、移动与搜索工作区文件
triggers: 文件操作,读取文件,写入文件,搜索文件
version: 1.1.0
suite: builtin
---

## 文件操作

1. 先用 list_files / search_files 确认路径与内容，再读写。
2. 读取大文件用分段参数，避免一次塞满上下文。
3. 修改文件优先用 edit_file 做精确替换，整文件重写才用 write_file。
4. 不要删除或覆盖用户未确认的文件；涉及工作区外路径先询问。
