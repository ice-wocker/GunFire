# 贡献指南

## 四条底线

1. **不引第三方依赖**。只允许 AndroidX 框架层（`androidx.*`）。想加别的库，先在 Issue 里说明为什么框架层做不到。
2. **不申请权限**。目前 `AndroidManifest.xml` 里除 AndroidX 自动注入的内部广播权限外为空。任何新增权限都要有充分理由。
3. **体积敏感**。Release APK 目前 623 KB。提交前跑一次 `assembleRelease` 对比体积，涨超过 50 KB 需要在 PR 里说明。
4. **游戏逻辑必须可测**。`GameWorld` 刻意不引用任何 Android 类，就是为了让规则能在 JVM 上跑单测。新规则请配套测试。

## 开发

```bash
# 需要 JDK 17 + Android SDK 35（build-tools 35.0.0）
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew testDebugUnitTest   # 27 个单测
./gradlew lintDebug
./gradlew assembleDebug
```

## 提交规范

- 提交信息用 `type(scope): 说明`，type 取 `feat` / `fix` / `perf` / `refactor` / `docs` / `test` / `build` / `ci`。
- 说明写清「为什么」而不是「改了什么」——diff 已经说明改了什么。
