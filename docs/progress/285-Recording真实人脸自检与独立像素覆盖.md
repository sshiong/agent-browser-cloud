# Recording 真实人脸自检与独立像素覆盖

> 日期：2026-10-19
> 基线：21fb633c259e002867c62e9dd9f175d091dec111
> 范围：原十一项中的 Recording 全帧非文本隐私检测与能力准入。

## 发现的缺口

1. 旧启动自检只验证人脸 Cascade 非空，实际功能样本只有 OCR 与 QR。注入“模型可以
   加载、但检出恒为零”的人脸组件，其余真实 OCR/QR 继续运行，旧自检仍成功。
   精确回归 **7 tests / 1 failure**，失败为预期异常未抛出。
2. 给真实人脸样本增加独立标注后，沿用旧四像素边距的中间版本返回视觉残留零，但
   最终 JPEG 的标注区域仍有未遮罩像素。新增精确断言失败：**10 tests / 1 failure**。
   这证明同一分类器复检为零不能替代已知样本的独立像素覆盖检查。

## 样本与用途

使用 James S. Davis 于 1984-01-20 拍摄的 U.S. Navy 公开领域照片 NH 96919-KN。
[Matplotlib 的精确来源提交与公开领域声明](https://github.com/matplotlib/matplotlib/commit/5da2608950866c9e821177b20b6cfadfd76d3b7a)
随资产 README 保留摄影署名、原始链接、SHA-256 与独立标注。

原图为 512×600，固定标注区域为 `(156,140,374,345)`；该区域经图片检查确定，不从
分类器预测框生成。图像只用于本地检测/遮罩，不做人脸身份识别或外部模型调用。
该第三方资产声明不替项目权利人选择根许可证，原 MIT/UNLICENSED 决策 Gate 保留。

## 实现

- 抽取生产使用的人脸区域检测函数，在每轴原检测框外新增 10% 安全边距、最少四
  像素，再沿用原有有界裁剪；QR 区域和 OCR 规则保持原逻辑。
- 启动自检先有界读取并验证样本的精确 SHA-256，再要求真实人脸检测覆盖固定标注。
  继续调用完整生产 scan，对返回 JPEG 重新解码，检查尺寸及标注区域确实为遮罩像素。
- 文件缺失、内容变化、检测为空、区域不覆盖标注、计数正确但返回未遮罩像素，均
  拒绝就绪。生产与测试镜像在同一路径安装照片及署名；生产资产只读。
- 继续输出原 v2 ready/scanVersion，API/Protobuf/SDK/数据库、能力名称和历史播放
  契约没有变化。增加的是功能证明与保守边距，不是新增侧脸或医学影像分类能力。

## 验证

- `make test-recording-privacy`：Debian、真实 Tesseract/OpenCV、非 root、测试步骤
  禁网，**12 tests / OK**。真实照片通过实际扫描子进程验证单行 JSON、遮罩区域、
  最终 JPEG Hash、零残留，并保留非人脸图像内容。
- 原四项 JPEG/最终像素围栏、真实 OCR/QR 自检和两帧多 QR NDJSON 测试继续通过。
- 非 root、只读根文件系统、禁网运行最终 `--self-test`：exit 0，唯一一行不超过
  512 字节且只有精确的 `ready=true/scanVersion=v2` 字段。
- Python 语法、diff 检查通过。基线 21fb633 的主 CI **39207919886**、Desktop
  **39207919952** 最终 exact SHA JSON 已复核：扫描、Integration/GameDay、Operator、
  Windows/macOS 全成功；本次改动仍须自己的 CI。

私有证据：`/tmp/agentbrowser-face-selftest-old.log`、
`/tmp/agentbrowser-face-coverage-old.json`、`/tmp/agentbrowser-face-coverage-regression.log`、
`/tmp/agentbrowser-face-selftest-final.log`、
`/tmp/agentbrowser-face-selftest-final-readonly.json` 及对应进程退出记录。

## 剩余范围

单个公开正面人脸样本不能代表客户视觉分布、所有姿态、证件或医学影像。客户授权
Dataset、扩展类别、目标云 Legal Hold/IAM、真实 OTP/MFA/支付、具体 Provider 协议/
服务端取消回执、目标 Linux/硬件/多 Region 长稳、组织与许可证 Gate 仍未完成；完整
区域授权/动作/独立 Outcome 的产品闭环继续保留。总目标保持 active。
