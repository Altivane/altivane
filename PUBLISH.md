# Altivane 手动发布 GitHub 流程

> 适用：Windows + Git Bash。假设仓库在 `I:\dev\ride`，已在本地完成 git init 与提交。

## 0. 前置检查

```bash
cd /i/dev/ride
git status              # 工作区干净（有改动先 commit）
git log --oneline -3    # 确认提交历史
```

## 1. 开代理（推送 GitHub 必需）

打开你本机的代理软件，确认 HTTP 代理端口（常见：7890 / 10809 / 12146）。给 git 单独配置（只影响 git，不动全局环境变量）：

```bash
# 把 7890 换成你的实际端口
git config --local http.proxy http://127.0.0.1:7890
git config --local https.proxy http://127.0.0.1:7890

# 验证连通（应返回 HTTP/2 200 或 301）
curl -I https://github.com
```

推送完成后取消代理（可选）：

```bash
git config --local --unset http.proxy
git config --local --unset https.proxy
```

## 2. GitHub 网页创建空仓库

1. 打开 https://github.com/new
2. Repository name：`altivane`
3. Description：`Offline-first workout tracker for cycling & hiking (Kotlin + Compose + MapLibre)`（可改）
4. **Public / Private 任选**；**不要**勾选 Add README / .gitignore / license（本地已有，勾了会冲突）
5. 点 **Create repository**，记下仓库地址，形如 `https://github.com/<你的用户名>/altivane.git`

## 3. 关联远程并推送

```bash
cd /i/dev/ride
git remote add origin https://github.com/<你的用户名>/altivane.git
git push -u origin master
```

首次推送会弹 Windows 凭据窗口（或浏览器 GitHub 登录）：
- 选 **Sign in with your browser** 最省事；
- 如果弹的是用户名/密码框：密码处要填 **Personal Access Token**（不是账号密码）。
  Token 生成：GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token → 勾 `repo` 权限 → 生成后复制粘贴（只显示一次，存好）。

推送成功标志：`Branch 'master' set up to track remote branch 'master'`。

## 4. 发 Release（可选但推荐）

```bash
git tag v0.8.44 -m "v0.8.44: in-app language switch (zh/en), full i18n, compass cleanup"
git push origin v0.8.44
```

然后打开 `https://github.com/<你的用户名>/altivane/releases/new`：
1. Tag 已存在则选 `v0.8.44`；
2. 标题：`v0.8.44`；
3. 正文写更新要点；
4. 附件拖入 `I:\dev\ride\altivane-v0.8.44-debug.apk`（如果要发 apk）；
5. **Publish release**。

## 5. 以后每次更新的固定套路

```bash
cd /i/dev/ride
git add -A
git commit -m "vX.Y.Z: 变更摘要"
git push
```

## 常见问题

| 现象 | 处理 |
|---|---|
| `Could not resolve host github.com` | 代理没开或端口不对，回第 1 步 |
| `403` / `Authentication failed` | Token 没勾 `repo` 权限，或用密码登录被拒——重新生成 Token |
| `rejected (fetch first)` | 网页建仓库时勾了 README，执行 `git pull origin master --allow-unrelated-histories` 后再推 |
| 想换账号 | `git remote set-url origin https://github.com/<新用户名>/altivane.git`，Windows 凭据管理器里删掉 GitHub 凭据 |
| 查看当前代理配置 | `git config --local --get-regexp proxy` |
