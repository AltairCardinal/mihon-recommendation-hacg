# 琉璃神社订阅源

本仓库维护独立订阅规则、签名数据包与站点回归证据。默认发布方式为声明式规则，Android 与 Desktop 使用相同 JSON 及固定宿主解释器。站点基址、栏目、正文与历史选择器、书目字段和提取表达式均在 rules/subscription-rules.json。

## 添加仓库

在「更多 → 订阅 → 订阅管理 → 订阅仓库」添加下列地址，核对发布者指纹后确认。在订阅管理中选择琉璃神社并订阅漫画栏目。

    https://raw.githubusercontent.com/AltairCardinal/mihon-recommendation-hacg/main/catalog/repo.json

成人来源的可见性跟随「更多 → 设置 → 浏览 → NSFW 图源」。其说明为「在图源、插件列表和订阅中显示」。

发布者 SHA-256 指纹为：

    bc7bc9e21b266a86673b200a1320a32d7e392029b478a8e2b5c15cb94bd0f612

仓库同时声明 subscription_index_v1 和旧 recommendation_index_v1。支持声明式规则的新客户端优先使用前者，旧 Desktop 客户端继续读取独立 JVM 插件 1.0.0。两种产物沿用同一长期发行证书，规则版的包名与工厂 ID 保持一致，版本为 1.1.0。

## 规则数据包

rulesJar 生成包含 subscription-rules.json 和许可文件的纯数据 JAR。包中禁止类、脚本、私钥或未签名 payload。正式签名包位于 releases/1.1.0/mihon-subscription-hacg-rules.jar，目录保存签名包的 SHA-256。客户端先核对传输摘要，再逐 payload 验证发布者证书，最后读取规则中的身份、内容分级和允许域名。目录中的展示字段只供安装前提示。

发行私钥仅存本机 ignored .release 目录，由维护者备份。publisher.cer 是公开证书。更换域名、提高内容分级或更换发行身份需要宿主重新确认相应权限，普通规则更新使用原证书。

## 能力与边界

- 稳定工厂 ID 为 hacg，栏目 ID 为 comic。订阅动作包含安装、权限确认及首次获取。
- 发现读取漫画 RSS，历史每次只读取一页，游标绑定实例和栏目。
- 正文转换为原生段落、图片、链接、引用与剧透。脚本和表单丢弃，外域图片需要显式域名许可。
- 书目保留原名、来源别名、期刊和合集类型、收录作品以及作者和社团身份。网页发布者、出版社与作品署名分开。
- 缓存元数据升级使用原列表标题与正文，离线执行。
- 评论只读，当前只声明有冻结证据的热门排序，分页绑定实例、文章和排序。静态评论配置作为数据提取，宿主不执行网页 JavaScript。
- 请求全部经过实例独立的宿主 HTTP 上下文。失败、取消、结构变化和过大响应显式报告。

## 构建与回归

需要 JDK 21，以及已发布到本地 Maven 的 org.mihon:recommendation-api:1.1.0 和 org.mihon:recommendation-rules:1.0.0。通过参数指定本地 SDK 仓库。

    gradlew -PrecommendationSdkRepository=file:///absolute/path/to/sdk-maven test --tests mihon.recommendation.hacg.RuleCorpusTest --tests mihon.recommendation.hacg.RuleHttpTest rulesJar

RuleCorpusTest 对声明式工厂执行连续 30 篇人工金标准和离线升级比较。RuleHttpTest 使用原始 HTTP 形状检查发现、历史、正文、评论、图片、失败与取消。src/test/resources/hacg-30/ 保留录制列表顺序、原文和人工判断，禁止由当前提取器生成期望值。这些证据留在本独立仓库。

旧 HacgCorpusTest、HacgHttpTest 与 HacgPackagingTest 继续覆盖兼容 JVM 插件，通过 pluginJar 构建其旧产物。本地 scripts/preview_package.py 仅用于旧插件回环签名测试。

当前规则迁移的 30 篇样本和 9 项 HTTP 行为测试已通过。双端安装及 Android 签名运行时结果由本次宿主交付报告记录。录制样本通过不代表实时网站兼容验收。

解析规则与固定证据源自既有独立插件，沿用随附 Apache-2.0 许可。
