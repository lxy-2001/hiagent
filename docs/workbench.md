# HiAgent 工作台

Feature009 提供中文对话工作台：登录、会话与历史、执行步骤、取消、工具审批、引用来源、两项确认记忆，以及明暗主题与移动布局。模型调用和密钥保留在后端。

## 构建与启动

需要 Java 17、PowerShell 7，以及 Node 24.15 以上的24系列版本。前端依赖由锁文件固定。

```powershell
# 在仓库根目录；先执行全仓清理验证，再生成前端资源
.\mvnw.cmd clean verify
pwsh -NoProfile -File scripts/build-workbench.ps1
# 启动前，按 docs/configuration.md 加载自己的后端环境变量
java -jar agent-demo/target/agent-demo-0.1.0-SNAPSHOT.jar
```

打开本机服务端口（默认8080）。浏览器只访问同源 API，不填写模型密钥。使用初始化管理员账号登录；令牌仅保留在页面内存，刷新页面需要重新登录。不要把私有 `.env` 复制到前端目录。

`build-workbench.ps1` 依次执行锁文件安装、类型检查、lint、单元测试、前端构建及 `-Pworkbench` 打包，任一步失败均返回非零。普通 Maven 构建仍不要求 Node，并使用原始 Demo 页面。`clean` 会删除生成的工作台资源，之后必须重新运行工作台构建脚本。

## 本机开发

```powershell
npm --prefix agent-demo/frontend ci --ignore-scripts
# 默认代理到本机8080；可选择其他本机HTTP端口，不能配置远端
$env:WORKBENCH_BACKEND_URL='http://127.0.0.1:8080'
npm --prefix agent-demo/frontend run dev
```

前端不会自动加载 `.env`。后端配置请使用现有本机启动方式；不要将服务端令牌写入 `VITE_*`。

## 独立验收

```powershell
# 首次准备本机 Playwright 浏览器
npm --prefix agent-demo/frontend exec -- playwright install chromium firefox webkit
pwsh -NoProfile -File scripts/verify-workbench.ps1
```

验证器在临时目录创建白名单源码副本，排除私有配置、旧构建产物及用户文件，核验复制hash，再安装、测试和打包。浏览器访问实际JAR中的页面及hash资源；离线测试模型、H2与本机MCP fixture由外部测试类路径加载，不进入生产包。所有模拟状态在测试名中标明。结果在 `.ua/009-workbench/delivery/result.json`，临时副本保留以便定位失败。

`build-info.json`记录源码版本、工作区是否有修改、源码与产物hash；元数据在JAR的META-INF中，不作为公开静态页面。验收包含错误构建/测试的非零退出码检查，不能用已有target替代新构建成功。

## 能力边界

- 步骤事件是任务过程，不是逐token文字流。审批通过不等于执行成功；UNKNOWN不自动重放。
- 来源按钮只绑定后端返回的当前任务引用；远端图片不加载，来源路径不作为可访问文件链接。
- 对话历史从最近20轮开始，旧记录按固定水位分页；刷新后从服务端恢复活动任务。
- 记忆仅包含现有两个确认槽位，冲突后须核对最新值。
- 演示验收默认不调用付费模型；离线测试通过不等于真实模型效果验收或已部署生产。
- Windows WebKit使用键盘完成核心smoke；该环境鼠标自动化存在挂起限制，不声明已验证其全部鼠标交互。
