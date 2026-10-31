# 更新包挂 Cloudflare（路线 A：只加一个镜像前缀）

app 侧改动只有一处：`DownloadSourceSet.MIRRORS` 多一个有条件的自建源，前缀由
`app/build.gradle.kts` 的 `UPDATE_CF_MIRROR_PREFIX` 注入（构建期可覆盖）：

```bat
gradlew.bat :app:assembleRelease -PcfMirrorPrefix=https://gh.example.com/
```

留空（默认）时行为与改造前完全一致：只有直连 + gh-proxy + ghproxy.net。

## URL 形态

`withPrefix()` 的语义是「前缀 + 完整原始 URL」，所以 app 会请求：

```
https://gh.example.com/https://github.com/Suife-yuanxing/zhijian-schedule/releases/download/v1.0.16/PaperTimetable-v1.0.16.apk
```

Worker 取最后一段文件名，302 到 `https://dl.example.com/PaperTimetable-v1.0.16.apk`。
字节由 R2 边缘直出，Worker 不碰数据流。

## 上线前必验（三条，缺一条就退回单连接或者下错文件）

```bat
rem 1) 必须是 206 + Content-Range，否则 decideRangeSupport 判 Unsupported，8 路分片废掉
curl -sI -H "Range: bytes=0-1023" https://dl.example.com/PaperTimetable-v1.0.16.apk

rem 2) Content-Length 必须等于 GitHub assets[].size，app 拿它当进度分母兜底
curl -sI https://dl.example.com/PaperTimetable-v1.0.16.apk | findstr /i content-length

rem 3) 安装包必须与官方 asset 字节一致 —— 镜像候选受 digest 门槛约束，
rem    sha256 不匹配时 verifyAndClearChunks 会整包判失败
certutil -hashfile PaperTimetable-v1.0.16.apk SHA256
```

GitHub Release 的 `digest` 字段形如 `sha256:...`，R2 上的对象必须与它逐一相同，
否则 `DownloadSourceSet.availableSources` 会直接砍掉整条镜像链（设计 §3.2）。

## 成本

R2 出口流量免费；免费额度 10 GB 存储 / 100 万 Class A / 1000 万 Class B 每月。
8 路分片下一次 APK 约 10 次 GET，10 万次下载才 100 万次请求。R2 需要绑卡但只在超额时计费。

## 不要把大文件放在 Pages / Workers 静态资源里

单文件上限 25 MiB，而 release APK 约 65.8 MiB，只能放 R2。
