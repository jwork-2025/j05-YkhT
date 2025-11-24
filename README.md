[![Review Assignment Due Date](https://classroom.github.com/assets/deadline-readme-button-22041afd0340ce965d47ae6ef1cefeee28c7c493a6346c4f15d667ab976d596c.svg)](https://classroom.github.com/a/iHSjCEgj)
# J05

游戏视频已上传小破站（见链接https://www.bilibili.com/video/BV1RuUGByEE7/）。

---

  # J05 — HuluSnake（含录制/回放）

  本仓库基于课程示例实现了一个简易的“贪吃葫芦（Hulu Snake）”游戏，并扩展了存档与回放功能。项目采用 LWJGL + OpenGL 做渲染，窗口与输入基于 GLFW；回放支持两种模式：基于关键帧（JSONL）的可视回放与基于 RNG seed + 输入事件的 simulation 回放。

  目录说明：

  - `src/main/java`：全部源码（引擎 + 游戏 + 回放/录制模块）
  - `recordings/`：默认录制输出目录（JSONL，每行为一个事件/关键帧）
  - `compile.sh` / `run.sh`：Bash 下的编译与运行脚本
  - `README.md`：本文件

  ## 核心概念（快速回顾）

  - Scene：场景生命周期管理（`initialize/update/render/clear`），示例：`MenuScene`, `HuluSnakeScene`, `ReplayScene`。
  - GameObject：实体，可组合 Component（如 `TransformComponent`、`RenderComponent` 等）。
  - RecordingStorage：存储抽象（读/写/列举），默认实现为 `FileRecordingStorage`（JSONL）。
  - RecordingService：负责采样输入、周期记录关键帧并异步写入 storage。
  - ReplayScene：解析 JSONL，按时间插值重建对象并渲染；若 recording 包含 RNG seed，可使用 simulation 模式更精确地复现游戏逻辑。

  ## 录制格式简介

  - 每一行是一个 JSON 对象，常见 `type`：`header` / `input` / `keyframe` / `spawn` / `destroy`。
  - `header`：包含版本、窗口宽高与可选 `seed`（用于 simulation 回放）。
  - `input`：记录按键 press/release 以及时间戳 `t`。
  - `keyframe`：周期记录实体的 `id,x,y,rt,w,h,color`（render info 可选），用于插值渲染。
  - `spawn` / `destroy`：记录对象生成/销毁事件，simulation 模式会参考这些事件恢复逻辑。

  实现要点：`RecordingService` 使用队列异步写入文件；`ReplayScene` 对 keyframe 做排序与时间归一化，并提供线性和“曼哈顿”插值，以保证链式实体（如蛇身）视觉稳定性。

  ## 编译与运行（Bash / Linux / macOS）

  1) （可选）如果需要 LWJGL 本机库：

  ```bash
  ./download_lwjgl.sh
  ```

  2) 编译并运行（脚本会收集 `src/main/java` 下所有源码并编译，然后运行 HuluSnake）：

  ```bash
  ./run.sh
  ```

  说明：`compile.sh` 会生成 `build/sources_compile.txt`（源码列表），并使用 `javac -encoding UTF-8 -d build/classes @build/sources_compile.txt` 编译。

  ## 在 Windows（PowerShell）下编译与运行

  在 PowerShell 中可执行以下命令（在项目根目录）：

  ```powershell
    # 清理并创建输出目录
    Remove-Item -Recurse -Force .\build\classes -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path .\build\classes | Out-Null

    # 编译所有源码为 UTF-8
    $srcs = Get-ChildItem -Path .\src\main\java -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
    javac -encoding UTF-8 -d .\build\classes $srcs

    # 编译成功再运行
    if ($LASTEXITCODE -eq 0) {
        java -cp .\build\classes com.gameengine.example.HuluSnake
    } else {
        Write-Error '编译失败'
    }
  ```

  注意：若出现 `UnsatisfiedLinkError` 之类的本机库错误，请确保 LWJGL 的 jars 与 native 库已按说明就绪，或使用 `-Djava.library.path` 指定 native 路径。

  ## 回放验证

  1) 手动录制：运行游戏并使用“开始录制”（或打开自动录制），完成一些操作后退出，录制文件会在 `recordings/` 中生成（`.jsonl`）。
  2) 文件检查：打开 `.jsonl`，应包含 `header`、若干 `input`、`keyframe`、以及 `spawn`/`destroy` 行。检查数字是否使用小数点 `.`（locale 影响可能导致解析问题）。
  3) 回放：可通过菜单进入回放，或使用命令行运行 `ReplayTest`：

  ```bash
  java -cp build/classes com.gameengine.example.ReplayTest recordings/hulusnake-<timestamp>.jsonl
  ```

