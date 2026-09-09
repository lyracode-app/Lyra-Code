# Lyra 桌宠包开发指南（API 2）

桌宠使用 ZIP 分发，包内包含清单、JavaScript 模块、配置和媒体文件。默认「Lyra 星猫」也使用相同协议。Android 宿主控制悬浮窗口、任务和确认，脚本负责外观、动画与交互反馈。

## 导入与自定义

长按桌宠 700 毫秒，或在设备交互页打开「桌宠与截图设置」。可以调整大小（40–240 dp）、自动吸边、等待时间（500–60000 毫秒）、隐藏比例（0–80%）、展开/收起不透明度、声音开关及音量。默认保持 3.5 秒吸边、隐藏一半、收起不透明度 42%。第一次点击唤醒，再次点击开关对话；拖动移动位置。

选择「导入宠物包」载入 ZIP；「导出当前宠物包」导出代码与全部资源，可直接解压修改、重新压缩分享。导出保留包作者的配置文件；在应用中修改的个人参数独立保存在本机，不写回导出的源码。导入会加入桌宠库并切换到新样式，不覆盖已有样式。点「切换桌宠」可随时切换，每只桌宠分别记住脚本参数；宿主大小、透明度和截图模型开关等设置全局共享。选择默认星猫也不会删除其他桌宠。列表内可移除不再使用的样式，移除当前桌宠会切回星猫；原始 ZIP 不会删除。

可直接导入的示例：

- [Minty · 薄荷 ZIP](desktop-pet-examples/minty-lyra-v2.zip)：将社区 Codex 图集按公开 API 适配到 Lyra，包含原作者 MIT 许可，支持九组动作和十六个视线方向。
- [默认星猫 ZIP](desktop-pet-examples/lyra-starcat-v2.zip)：SVG/CSS 动作与模块生命周期。
- [动画入门 ZIP](desktop-pet-examples/animation-starter-v2.zip)：同一角色的 GIF、APNG、PNG 帧序列，设置页可切换来源和 PNG 帧率。
- [动画入门源码](desktop-pet-examples/animation-starter/manifest.json)：可直接修改图片、JSON 和脚本。

旧 JSON（API 1，最多 2 MiB）仍可导入，也可导出为清单内嵌 HTML 的兼容 ZIP。旧接口见 [API 1 文档](DESKTOP_PET_SCRIPTING_API1_ZH.md)。新包应采用 API 2。

## 目录自由与清单

ZIP 可以直接包含文件，也可以在外面套一个包名目录。必须只有一个 `manifest.json`，所有文件都在该清单所在目录之内。除清单外没有固定目录；入口与配置由清单指定，资源由脚本按包根路径引用。例如：

```text
MyDeskPet/
  manifest.json
  config.toml
  data/dialogues.json
  data/states.json
  data/actions.json
  assets/sprites/idle/001.png
  assets/sprites/idle/002.png
  assets/sprites/walk.apng
  assets/sprites/tap.gif
  assets/ui/menu.png
  sounds/bgm/theme.ogg
  sounds/sfx/tap.wav
  scripts/main.js
  scripts/behaviors/idleCtrl.js
```

```json
{
  "apiVersion": 2,
  "id": "my.deskpet",
  "name": "我的桌宠",
  "version": "1.0.0",
  "author": "你的名字",
  "entry": "scripts/main.js",
  "config": "config.toml",
  "controls": [
    {"key":"fps","label":"动画帧率","type":"range","min":1,"max":30,"default":12},
    {"key":"roam","label":"随机爬动","type":"boolean","default":false},
    {"key":"color","label":"主题颜色","type":"color","default":"#70A4FF"},
    {"key":"pose","label":"默认姿势","type":"select","options":["idle","walk"],"default":"idle"},
    {"key":"greeting","label":"问候语","type":"text","default":"你好！"}
  ]
}
```

`apiVersion/id/name/version/author/entry` 必填；入口必须为 `.js` ES module。`config`、`controls` 可省略。`id` 为字母开头的字母、数字、点、下划线或连字符，最多 80 字符。

`controls` 最多 16 项，key 为字母开头的字母/数字/下划线，最长 40 字符。`range` 必须有 min/max/default，`select` 有 1–32 个不重复选项，`text` 最多 200 字符。未声明的自定义参数不出现在宿主设置页。大小等宿主键是保留键，不应用作自定义 key。参数的含义由脚本实现，宿主不会凭名字生成行为。

## config.toml 配置约定

当前实现使用明确的 **TOML 标量子集**，不是完整 TOML 解析器：支持 `[pet]`、`[controls]` 两个分区，值支持双引号字符串（JSON 转义）、有限数字、true/false 和行尾注释；不支持数组、多行字符串、内联表及重复分区/键。复杂的状态机、对话库与动作列表放在 JSON 中，不在 TOML 中写代码。

```toml
[pet]
size = 96
autoDock = true
dockDelayMs = 3500
dockFraction = 0.5
dockOpacity = 0.42
activeOpacity = 1.0
soundEnabled = false
volume = 0.5

[controls]
fps = 12
roam = false
color = "#70A4FF"
pose = "idle"
greeting = "你好！"
```

优先级：宿主默认值/controls.default → config.toml → 用户在设置页保存的值。数值会被宿主限制在支持范围内。最终配置通过 `config` 事件及 `api.config` 提供，脚本参数与宿主参数位于同一层。

## 标准生命周期与模块

入口默认导出含以下三个函数的对象。`mount` 可以异步加载数据；加载期间的宿主事件会等待 mount 完成再分发。`onEvent` 建议保持同步，异步任务自行捕获错误。`unmount` 应清理脚本自行创建的计时器、监听器和音频。

```javascript
import { choosePose } from './behaviors/idleCtrl.js';
let sprite, states;
export default {
  async mount(api) {
    states = await api.json('data/states.json');
    sprite = api.createSprite(); // 默认插入 api.root
    await sprite.play({directory: 'assets/sprites/idle', fps: 12});
  },
  onEvent(type, data, api) {
    if (type === 'state') {
      const animation = choosePose(data.state, states);
      sprite.play(animation).catch(console.error);
    }
    if (type === 'tap') sprite.element.animate(
      [{transform:'scale(1)'}, {transform:'scale(.9)'}, {transform:'scale(1)'}],
      {duration: 300});
  },
  unmount() { sprite?.destroy(); }
};
```

通过相对 `import` / `import()` 按需加载模块；不能导入 npm 包名或在线 URL。可用 CSS、SVG、Canvas、DOM 和本地字体制作任意外观，CSS/HTML 中的资源使用 `api.asset()` 生成的 URL。宿主提供透明正方形 `api.root`，资源应自适应宽高，建议保留很少空白边距。

## 动图与 PNG 帧序列

```javascript
const sprite = api.createSprite();
await sprite.play('assets/sprites/tap.gif');
await sprite.play({src: 'assets/sprites/walk.apng'});
await sprite.play({directory: 'assets/sprites/idle', fps: 12, loop: true});
await sprite.play({
  frames: ['assets/sprites/idle/002.png', 'assets/sprites/idle/001.png'],
  durations: [100, 250], // 每帧毫秒；省略时使用 fps
  fps: 12,
  loop: false,
  onComplete() { console.log('动作完成'); }
});
```

- GIF/APNG 使用设备 WebView 的原生动画解码，保持文件自带的帧时长/循环次数；`fps`、`loop`、`durations` 只控制 PNG 帧序列。也支持普通 PNG/JPEG、SVG 和 WebP 图像。
- `directory` 收集目录及子目录中的 `.png`，按自然顺序排序（1、2、10）；要求精确顺序时提供 `frames`。
- PNG 帧率 1–60 fps，每帧时长至少 16 毫秒。更换动画自动取消旧 PNG 计时器。`play()` 在首帧解码后完成，整段非循环 PNG 动作结束使用 `onComplete`，不要把 play Promise 当作整段动画完成。
- `sprite.stop()` 停止 PNG 帧计时；GIF/APNG 的播放由图像解码器控制。`sprite.destroy()` 移除图像并释放计时器，适用于所有格式。
- `sprite.element` 为实际 `<img>`，可设置滤镜、旋转、缩放、透明度。多个 sprite 可放入自建容器组合角色、装饰和气泡。
- PNG 序列解码使用浏览器缓存，不预先把整个序列解码进内存。建议控制图片尺寸与帧数；媒体文件压缩体积小不意味着解码内存小。

## 数据池、音频与交互

`await api.json('data/dialogues.json')` 读取包内 JSON，`api.files('assets/sprites/idle')` 返回包根相对路径。HP、饱食度、心情、随机对话、连招等都可由脚本维护；这些 JSON 是数据，没有预设业务逻辑。脚本内存随桌宠重载结束，当前 API 不提供用户状态写回存档。

```javascript
const sfx = api.audio('sounds/sfx/tap.wav', {volume: 0.7});
const bgm = api.audio('sounds/bgm/theme.ogg', {loop: true, volume: 0.3});
// 用户在设置页允许声音后：
bgm.play().catch(console.error);
sfx.play().catch(console.error);
// 可调用 pause() / stop() / destroy()
```

声音默认关闭；开启/关闭会重载桌宠。`api.audio` 将自身音量乘以宿主音量，并跟随 config 更新。音频格式是否可解码取决于系统 WebView；包内可用 WAV、MP3、OGG、M4A。低优先级 BGM、SFX 混音或互斥策略由脚本实现。直接创建 HTML Audio 应自行遵循 `soundEnabled/volume`；宿主关闭声音时也会禁止包内音频资源访问。

| 事件 | 数据与用途 |
| --- | --- |
| config | 宿主和脚本参数，首次加载及参数改变 |
| state | state、docked、packageName；状态包括 idle/thinking/executing/approval/completed/error |
| pointerdown | 桌宠内部触点 x/y |
| tap | 点击反馈，同时由宿主开关对话 |
| drag / dragend | 拖动位置 x/y / 拖动结束 |
| dock / reveal | side=left/right / 恢复完整显示 |
| operationRequested | 卸载任务请求，包含 packageName；尚未执行 |
| operationResult | id、operation、packageName、ok、exitCode；实际操作结果 |

宿主拥有拖动、点击与长按手势；不能依赖 DOM 点击菜单执行宿主操作。自定义菜单皮肤可显示在窗口内，用户可调参数由 controls 声明并在宿主设置页操作。脚本可使用事件坐标做视觉反馈。

```javascript
api.emit('move', {dx: 3, dy: -2}); // dp；每轴 -8..8；拖动/半隐藏时不移动
api.emit('reveal');
api.emit('dock');
api.emit('settings');
api.emit('requestUninstall', {packageName: 'example.app'});
```

桥接请求间隔至少 100 毫秒、每条最多 4096 字符。settings/requestUninstall 只在真实触摸后 700 毫秒内有效。卸载交给现有 Agent 与两次确认，脚本不能运行 Shell/Root 或批准自己的请求。吃掉应用动画应依据 operationResult.ok 播放完成效果，不能把 operationRequested 当成成功。

## 包校验与运行边界

- 压缩包最多 64 MiB，解压后总计最多 128 MiB，单文件最多 16 MiB，最多 2048 个 ZIP 条目（包含目录）；清单最多 2 MiB，配置最多 256 KiB。
- 禁止绝对路径、反斜杠、`..`、重复/大小写冲突路径，以及百分号、冒号、问号、井号等歧义字符。文件路径不超过 240 字符。ZIP 中的链接不会创建文件系统符号链接。资源走私有 HTTPS 来源；网络、file/content URI、iframe、worker、对象插件和系统权限均不开放。
- 导入先在临时目录解压、验证，再原子切换当前包。失败保留现有桌宠。导入只校验文件和清单，不执行 JavaScript；运行时语法错误或生命周期缺失显示备用猫图标，长按仍能打开设置。
- WebView 渲染进程崩溃/无响应时回退到默认包，仍不可用则保留原生入口。更换包、开关声音和关闭桌宠都会结束当前脚本运行。
- GIF/APNG 依赖设备 WebView 动画能力；支持情况以实际设备测试为准。对兼容性要求高时，同时提供 PNG 帧序列选项。


## 从 Codex 图集适配：Minty 实例

[原始仓库](https://github.com/somnusochi/minty-codex-pet)的 Minty 图集和模板采用 MIT 许可；本示例固定使用 `f98e0ac5605b1b2200c7c12e8df3e9b8f12655df`，包内保留原始 LICENSE 与来源。未运行上游安装脚本，没有修改原始 WebP 像素。

可复用的适配步骤：

1. 将原始图集复制到 `assets/spritesheet.webp`，保留许可及来源。
2. 新建 API 2 manifest、config、入口脚本。上游 `pet.json` 不是 Lyra manifest，不可直接当作 Lyra 包导入。
3. 用 `api.asset()` 加载图集、`api.json()` 加载行号和帧数；在 `mount` 创建 Canvas，在 `unmount` 取消 RAF 和计时器。
4. 用 Canvas 的 `drawImage()` 从原图取一个 192×208 图格，绘制到透明画布。CSS 使用 `object-fit: contain` 保持人物比例。
5. 将 Lyra 事件映射到动作，打包 ZIP 后从设置页导入。无需增加宿主私有接口或更改 JavaScript 桥接。

| 原图行 | 动作 | 有效帧数 | Lyra 映射 |
| --- | --- | --- | --- |
| 0 | idle | 6 | 空闲 |
| 1 / 2 | running-right / running-left | 各 8 | 拖动方向 |
| 3 | waving | 4 | 点击 |
| 4 | jumping | 5 | 完成 |
| 5 | failed | 8 | 失败 |
| 6 | waiting | 6 | 等待审批 |
| 7 | running | 6 | 思考、执行 |
| 8 | review | 6 | 动作预览 |
| 9 / 10 | look | 共 16 | 吸边视线与预览 |

Minty 图集为 8 列 × 11 行，空白图格不参与播放。个性化设置中的 `auto` 跟随事件；其他值可预览对应动画，`look` 循环展示十六方向。动画速度及随机移动也由 controls 声明。适配源码见 [Minty 入口](desktop-pet-examples/minty-lyra/scripts/main.js)。
