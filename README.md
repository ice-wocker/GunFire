# GunFire

一个纯粹的安卓枪战游戏。**623 KB**，**零第三方依赖**，**零权限**。

打开就能玩，不需要账号、不需要联网、不申请任何权限——不是「承诺不上传」，是技术上没有权限可用。

## 它长什么样

第一人称射击。你站在一个对称的射击场中央：

- **左半屏拖动** → 移动
- **右半屏拖动** → 转视角
- **右下角点击** → 开火
- 打空弹匣自动换弹，连射越久散布越大

场上有 9 个目标，全部击倒后刷新一波。HUD 实时显示得分、命中率、剩余目标、弹匣余量和帧率。

## 下载

从 [Releases](https://github.com/ice-wocker/GunFire/releases) 下载 `app-release.apk`。

- 最低 Android 7.0（API 24），目标 API 35
- 支持任意 ABI（纯 Java + OpenGL ES 2.0，无 native 代码）
- 用仓库内的演示 keystore 签名，可直接安装

也可以自己构建：

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease
```

需要 JDK 17 与 Android SDK 35。

## 体积是怎么压下来的

| 部分 | 大小 |
|---|---:|
| Release APK | **623 KB** |
| Debug APK | 3.1 MB |
| 类总数（含 AndroidX） | 675 |

做法很朴素：

1. **不用游戏引擎**。Unity / Godot / libGDX 一个都不引，直接写 OpenGL ES 2.0。整个渲染层 4 个类、约 500 行。
2. **不用素材**。没有贴图、没有模型文件、没有音频文件。世界是程序生成的几何体（立方体和圆柱），着色是单方向光的 Lambert 模型。
3. **不用 DEX 膨胀的依赖**。依赖只有 `androidx.appcompat`，其余全部手写。
4. **不开不必要的开关**。没有 `multiDexEnabled`，没有 Kotlin（Kotlin stdlib 单独就 1.5 MB+）。

## 代码结构

```
app/src/main/java/com/icewocker/gunfire/
├── MainActivity.java          唯一 Activity：GLSurfaceView + HUD 叠层
├── engine/                    渲染层，不碰游戏规则
│   ├── Vec3.java              三维向量
│   ├── Mat4.java              4x4 矩阵（投影 / 视图 / 模型变换）
│   ├── Shader.java            GLSL 程序编译与 uniform 定位
│   ├── Mesh.java              三角网格（立方体、圆柱）
│   └── Renderer.java          GLSurfaceView.Renderer 实现，兼游戏循环
└── game/                      规则层，不碰 Android
    ├── GameWorld.java         世界状态、物理、射线检测、计分
    ├── HudView.java           Canvas 绘制的 HUD
    └── TouchControls.java     触屏操作映射
```

**关键的一条边界：`GameWorld` 不 import 任何 `android.*`。**

这不是洁癖，是为了让游戏规则能在普通 JVM 上跑测试。`app/src/test/` 下的 27 个用例覆盖了射线检测、射击/换弹、移动碰撞、视角钳制、矩阵运算——它们不需要模拟器，毫秒级跑完。

渲染层每帧从 `GameWorld` 读只读快照，输入层往里写事件，两者之间没有锁。

## 测试

```bash
./gradlew testDebugUnitTest
```

27 个用例，覆盖：

- 射击消耗弹药、弹匣打空自动换弹、换弹从备弹补充
- 射线命中目标 / 被墙挡住 / 平行射线不产生假命中
- 击杀计分、命中率范围约束
- 移动不越出场地边界、被立柱阻挡、不能穿墙
- 俯仰角钳制在 ±1.5 弧度、偏航角完整回绕（不无限增长）
- 矩阵运算有限性、单位矩阵乘法、零向量归一化不产生 NaN

## 已知限制

- **没有敌人 AI**。目标是静止的靶子，只有轻微上下浮动。真正的枪战游戏需要会移动、会反击的敌人。
- **没有声音**。加音频要引 `SoundPool`（框架层，可以），但需要音频文件，会破坏「零素材」这条线。除非用程序合成波形。
- **没有多人**。这是单机游戏，没有网络代码。
- **场景是程序生成的**。没有关卡编辑器，没有地图文件。场地形状写死在 `GameWorld.buildArena()` 里。
- **手感未经真人验证**。散布、后坐力、摇杆灵敏度这些参数是按「能打中靶子」推出来的（见下），但没有人真的在手机上玩过。这需要你上手调。

## 开发中挖出来的问题

这个项目从零写完，编译通过不代表能玩。以下 5 个 bug 是写测试时暴露出来的，都真实存在过：

**1. 面向 -Z 时 forward 算成了 +Z**

`Camera.forward()` 里 yaw 的符号写反，导致视角和移动方向不一致——按下前进键会倒着走。修正后补了 `yawZeroPointsTowardNegativeZ` 和 `yawHalfPiPointsTowardPositiveX` 两条断言钉住。

**2. 偏航角只做单次回绕**

```java
// 错的：一次给 10000 像素增量就残留到 1571 弧度
if (yaw > Math.PI) yaw -= 2 * Math.PI;

// 对的：必须循环归约
while (yaw > Math.PI) yaw -= 2 * Math.PI;
while (yaw < -Math.PI) yaw += 2 * Math.PI;
```

**3. 立柱挡在自己的射击走廊上**

场地立柱按 `x = -14, -7, 0, 7, 14` 均匀排布，正好有一颗立在 `x=0`。玩家出生在中轴，向前直射先撞上这颗柱子——**站着不动都打不中任何目标**。把中轴那颗去掉，并保证中轴上必有一个目标。

**4. 散布角比目标张角还大**

这是最隐蔽的一个。散布幅度是 `spreadHeat * 0.035` 弧度，而 20 米外目标半高 0.35 只对应 **0.0146 弧度**——枪口比靶子宽 2.4 倍，连射必脱靶。实测 5 个随机种子共 300 发，**命中 0 次**。

```java
// 错的
float spread = spreadHeat * 0.035f;
// 对的：必须小于目标张角
float spread = spreadHeat * 0.012f;
```

后坐力同样从 `0.012 + heat * 0.02` 收敛到 `0.004 + heat * 0.006`，否则准星一路往上飘。

**5. 矮箱子把玩家挡死了**

碰撞检测原本按「与躯干范围重叠」判断，但判断条件写反，导致所有高度大于零的方块都拦路。改成只考虑**高于膝盖、低于头顶**的实体——矮箱可以跨过去。

## 许可

MIT。见 [LICENSE](LICENSE)。
