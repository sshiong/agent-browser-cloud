# Recording 正式 Node 事件投影与 v2 播放闭环

> 日期：2026-10-29
> 基线：089ce46574b63c1a194652aead726c62ae4e7090
> 状态：本机正式 Recorder/Helper/mTLS/Inbox/PG/API 链通过；CDP 与扫描器为协议夹具。

新增 make test-recording-node-projection，使用现有随机 loopback PG/Redis/MinIO 和
mTLS Node/Java 运行器。通过正式 API 创建启用 videoRecording 的环境、启动、等待
两个安全帧、停止到 HIBERNATED；不直接插入录制元数据或最终事件。

核验正式 Recorder 生成的 recordingId 与 API v2 投影一致，PG Recording 与 Inbox 具有
同一事件ID，Node SQLite Journal 对该事件已确认 delivered=1，数据库仅1行。跨租户
列表404。正式用途绑定播放 Grant 兑换后，独立下载每个分段，核验长度、分段 Hash、
逐帧 JPEG Hash、版本2/VERIFIED与总帧数。通过结果：2帧/1段，两个成功 marker。
本分支在完整 Integration 外单独运行，只有所有断言通过才设置 completion guard。
CI 新增同一入口；本提交仍须精确 SHA 核验。

新测试实际发现两个此前夹具隐藏的问题：

- Java Mapper 使用实体ID的 requireText 限制 Manifest 对象Key为128字符，正常路径会
  超长而被 INVALID_EVENT 拒绝。改为非空与 UTF-8 1024字节上限，保留 Tenant根、精确
  Session/Recording后缀、遍历与反斜线拒绝。单测验证1024字节可接受、1025字节与
  字符数小但UTF-8超限拒绝；未知版本和旧有范围拒绝测试保留。
- Node 播放入口仍拒绝v2，而Helper播放已接受1/2。入口现精确允许1/2，其他版本拒绝。
  原有Hash/时间/计数/身份/有效期校验不变，新的正式Grant→mTLS→Helper播放链实测v2。

第一次缺少安全帧，追查为旧fakeCDP只接受startScreencast却不发送帧。新增显式测试环境
变量，仅此分支启用16×16自行生成单色JPEG夹具和两帧ACK交互；第二帧仅在第一帧ACK
后发送。默认fakeCDP与完整Integration路径保持原行为。没有真实Chrome或OCR准确率声明。
第二次实际事件拒绝、第三次投影成功但兑换503均保留失败日志；503为Node版本检查，
没有加入通用HTTP重试。修复后完整专用链通过。

Java常规687项：657通过、30项专用PG条件跳过；这些PG已在progress308单独全通过。
全Rust Workspace严格Clippy通过；完整make test-integration退出0，播放、两种物理删除、
原生Hold mTLS/拒绝保留UNKNOWN及帧遮罩marker均true。源代码在Gate运行期间冻结。
089ce46主CI40823227744与Desktop40823227804精确SHA全成功。

OrbStack在本次续行时停止，按约定orbctl start后重验Running/orbstack/OS=OrbStack；
无Desktop或其他daemon测试。全部专用容器0、本轮网络0，两闲置MinIO测试镜像按
构建ID与无引用核验后移除。原8容器与旧空网络保留，清理报告在私有/tmp文件。

仍缺PG上传准入、默认upload_hold=None启用、Seal→Hold所有权交接/旧写入器围栏、
跨Node、目标云与原十一项其他外部/环境/组织Gate。此证据不等于真实Chrome采集→
正式mTLS事件→PG一条完整链；真实Chrome/真实扫描证据仍见progress307。
