# 琉璃神社推荐源（独立 JVM 插件）

本站点实现独立消费 `org.mihon:recommendation-api:1.1.0` 发布制品，不引用桌面宿主源码、数据库、Compose、DI 或漫画 Source。内容分级为成人，安装和绑定由宿主确认。当前只提供本地预览包，没有正式发行证书、远端发布或在线兼容承诺。

## 构建

需要 JDK 21、Gradle 8.14.4 缓存及依赖缓存；字节码目标为 JVM 17。SDK 仓库必须通过参数显式指定，目录内应具有 Maven 坐标结构：

```text
gradlew --offline -PrecommendationSdkRepository=file:///absolute/path/to/sdk-maven test --tests mihon.recommendation.hacg.HacgCorpusTest --tests mihon.recommendation.hacg.HacgHttpTest --tests mihon.recommendation.hacg.HacgPackagingTest pluginJar spotlessCheck
```

编译期 SDK、Kotlin 和协程由宿主提供；插件 JAR 只打包自身与私有 Jsoup、serialization 依赖。不使用额外 Class-Path、JAR 索引或宿主私有依赖回退。HTTP 全部经过每个实例的 `RecommendationHostContext.http`，不创建独立客户端、Cookie 存储或连接池。

## 能力与边界

- Factory `hacg`，稳定栏目 `comic`；声明旧来源 `hacg` 的占位栏目 `legacy` 绑定到 `comic`。该声明不会自动接管旧记录或订阅。
- DISCOVER 读取漫画 RSS；HISTORY 每次只返回一页并给出下一页游标，宿主决定数量上限。列表不包含正文。
- 正文转为可控原生块，丢弃脚本、表单和活动嵌入；元数据保留作品原名、来源别名、期刊/合集类型、收录作品与署名身份（包括未知）。网页发布者和出版社不冒充作者。
- 离线元数据升级使用原列表标题与缓存正文，不请求网站。
- 评论只读，首批仅声明已有冻结证据的热门排序；最新/最早在 HTTP 前明确返回不支持，不把自拟协议值作为站点证据。游标绑定实例、文章及排序；不提供发表评论、登录或投票。
- 初始域名权限仅为已确认的 `www.hacg.icu`。未核实的外域图片请求会明确失败，不放行通配域名；后续加入已确认图片域名需要新包及宿主扩权确认。
- 本插件是经确认运行的 JVM 代码，并非 OS 沙箱。失败、取消和过大响应不会伪装为空结果；已有缓存由宿主保留。

## 固定证据

`src/test/resources/hacg-30/` 保留连续30篇文章的原始精简 HTML、列表顺序和人工金标准。测试逐字段比较原名、别名、署名、署名别名/身份、作品类型和8个合集收录作品，并验证相同缓存的离线提取，禁止由当前提取器生成期望值。

`docs/evidence/2026-11-16-hacg-extraction-30.md` 是移出前的历史证据原文，其中旧路径及旧运行结果不代表本仓库已重新验证。当前仓库以本地测试报告为准，默认不请求原站；实时验证需另行授权，不自动联网。

解析代码及固定证据源自 Mihon Desktop 的既有推荐实现，沿用随附 Apache-2.0 许可；名称规范化仅保留实际使用的纯函数，作为本插件私有实现维护。

## 本地签名预览

```text
python scripts/test_preview_package.py
python scripts/preview_package.py --jar build/libs/mihon-recommendation-hacg-1.0.0-preview-plugin.jar --output .preview --base-url http://127.0.0.1:18781
```

运行签名测试前设置 `HACG_PLUGIN_JAR` 为真实 `pluginJar` 文件，`JAVA_HOME` 为 JDK。脚本创建30天的 `LOCAL PREVIEW ONLY` 自签身份，验证 JAR 后生成匹配的摘要、指纹及目录索引；仅接受显式回环 HTTP 地址。预览私钥、包和目录均在 ignored 目录，密码只用于本地测试，不能用于正式发布。重复运行保留同一预览身份；需要丢弃时只手动删除该预览目录，绝不作为正式发行者证书使用。
