# MP3 播放器 CloudBase 更新服务

- 环境：`chuya-d6gyub7awb35a8bf7`
- 函数：`mp3Update`
- 安装包：`releases/mp3-latest.apk`
- HTTP 路由：`mp3`

发布时必须先使用原车机安装包相同的调试证书签名 APK，再更新函数中的
SHA-256 与文件大小，最后部署函数和 `/mp3` 路由。客户端会同时校验大小与
SHA-256，校验失败时不会拉起安装器。
